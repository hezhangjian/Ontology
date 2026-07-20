#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/docker/scripts/compose.sh"
api=http://localhost:9080/api/ontology/v1
keycloak=http://localhost:8083
work_dir=$(mktemp -d)
pulsar_container=$(${compose} --profile '*' ps -q pulsar)
opensearch_container=$(${compose} --profile '*' ps -q opensearch)

cleanup() {
  docker start "${opensearch_container}" >/dev/null 2>&1 || true
  ${compose} --profile '*' exec -T ontology-core curl -fsS -X DELETE \
    'http://opensearch:9200/platform-ontology-objects/_doc/p08-restricted?refresh=true' >/dev/null 2>&1 || true
  rm -rf "${work_dir}"
}
trap cleanup EXIT INT TERM

fail() { echo "E:explorer-page failed: $1" >&2; exit 1; }
sql() { ${compose} --profile '*' exec -T postgres psql -U ontology -d ontology -Atc "$1"; }
wait_for_value() {
  query=$1 expected=$2 label=$3
  for _attempt in $(seq 1 60); do
    actual=$(sql "${query}")
    [ "${actual}" = "${expected}" ] && return 0
    sleep 1
  done
  fail "timed out waiting for ${label}; expected ${expected}, got ${actual}"
}

admin_password=$(tr -d '\r\n' < "${project_root}/docker/secrets/examples/keycloak_admin_password")
admin_token=$(curl -fsS -X POST "${keycloak}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode grant_type=password \
  --data-urlencode client_id=admin-cli --data-urlencode username=admin --data-urlencode "password=${admin_password}" | jq -er .access_token)

service_token() {
  client_id=$1 roles=$2 client_secret=$(openssl rand -hex 24)
  client_uuid=$(curl -fsS "${keycloak}/admin/realms/ontology/clients?clientId=${client_id}" -H "Authorization: Bearer ${admin_token}" | jq -r '.[0].id // empty')
  client_json=$(jq -n --arg id "${client_id}" --arg secret "${client_secret}" '{clientId:$id,enabled:true,protocol:"openid-connect",publicClient:false,clientAuthenticatorType:"client-secret",secret:$secret,serviceAccountsEnabled:true,standardFlowEnabled:false,directAccessGrantsEnabled:false}')
  if [ -z "${client_uuid}" ]; then
    curl -fsS -X POST "${keycloak}/admin/realms/ontology/clients" -H "Authorization: Bearer ${admin_token}" -H 'Content-Type: application/json' -d "${client_json}" >/dev/null
    client_uuid=$(curl -fsS "${keycloak}/admin/realms/ontology/clients?clientId=${client_id}" -H "Authorization: Bearer ${admin_token}" | jq -er '.[0].id')
  else
    curl -fsS -X PUT "${keycloak}/admin/realms/ontology/clients/${client_uuid}" -H "Authorization: Bearer ${admin_token}" -H 'Content-Type: application/json' -d "${client_json}" >/dev/null
  fi
  service_user=$(curl -fsS "${keycloak}/admin/realms/ontology/clients/${client_uuid}/service-account-user" -H "Authorization: Bearer ${admin_token}" | jq -er .id)
  role_json='[]' old_ifs=$IFS IFS=,
  for role in ${roles}; do
    role_value=$(curl -fsS "${keycloak}/admin/realms/ontology/roles/${role}" -H "Authorization: Bearer ${admin_token}")
    role_json=$(printf '%s' "${role_json}" | jq -c --argjson role "${role_value}" '. + [$role]')
  done
  IFS=$old_ifs
  curl -fsS -X POST "${keycloak}/admin/realms/ontology/users/${service_user}/role-mappings/realm" -H "Authorization: Bearer ${admin_token}" -H 'Content-Type: application/json' -d "${role_json}" >/dev/null
  curl -fsS -X POST "${keycloak}/realms/ontology/protocol/openid-connect/token" -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode grant_type=client_credentials --data-urlencode "client_id=${client_id}" --data-urlencode "client_secret=${client_secret}" | jq -er .access_token
}

viewer_token=$(service_token ontology-p08-e2e-viewer Viewer)
builder_token=$(service_token ontology-p08-e2e-builder Builder,Viewer)
admin_api_token=$(service_token ontology-p08-e2e-admin Admin,Builder,Viewer)
viewer_subject=$(printf '%s' "${viewer_token}" | jq -Rr 'split(".")[1] | gsub("-";"+") | gsub("_";"/") | @base64d | fromjson | .sub')
builder_subject=$(printf '%s' "${builder_token}" | jq -Rr 'split(".")[1] | gsub("-";"+") | gsub("_";"/") | @base64d | fromjson | .sub')

for service in apisix frontend hugegraph keycloak minio ontology-core opensearch postgres projection-worker pulsar; do
  service_state=$(${compose} --profile '*' ps --format json "${service}" | jq -r 'if type == "array" then .[0].State else .State end')
  [ "${service_state}" = running ] || fail "${service} is not running"
done

revision=$(sql "SELECT revision FROM control.ontology_revisions WHERE status='ACTIVE'")
employee_type=00000000-0000-0000-0000-000000000102
name_property=20000000-0000-0000-0000-000000000202
department_property=20000000-0000-0000-0000-000000000203
email_property=20000000-0000-0000-0000-000000000204
stamp=$(date +%s)
batch_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
idempotency="p08-explorer-${stamp}"
jq -n --arg batch "${batch_id}" --argjson revision "${revision}" --arg idempotency "${idempotency}" --arg stamp "${stamp}" '
  {batch_id:$batch,ontology_revision:$revision,action_type_id:"p08-fixture",action_version:1,preview_token_id:"p08-e2e",idempotency_key:$idempotency,requested_by:"p08-e2e",occurred_at:(now|todate),correlation_id:$idempotency,
   edits:[range(0;30)|{operation:"object.create",object_type_id:"Employee",object_id:("P08-"+$stamp+"-"+(if .<10 then "0" else "" end)+tostring),expected_version:null,relation_type_id:null,relation_id:null,source_object_type_id:null,source_object_id:null,target_object_type_id:null,target_object_id:null,properties:{name:("P08 "+$stamp+" User "+(if .<10 then "0" else "" end)+tostring),department:(if .%2==0 then "Research" else "Operations" end),email:("p08-"+$stamp+"-"+tostring+"@secret.invalid")}}]}' > "${work_dir}/batch.json"
docker cp "${work_dir}/batch.json" "${pulsar_container}:/tmp/p08-explorer-batch.json" >/dev/null
${compose} --profile '*' exec -T pulsar bin/pulsar-client produce persistent://platform/commands/mutation-batches \
  --key "${idempotency}" --files /tmp/p08-explorer-batch.json >/dev/null 2>&1
wait_for_value "SELECT status FROM control.projection_operations WHERE idempotency_key='${idempotency}'" PROJECTED "fixture projection"

auth_viewer="Authorization: Bearer ${viewer_token}"
auth_builder="Authorization: Bearer ${builder_token}"
auth_admin="Authorization: Bearer ${admin_api_token}"
curl -fsS "${api}/explorer/home" -H "${auth_viewer}" > "${work_dir}/home.json"
jq -e --arg type "${employee_type}" '.searchStatus=="HEALTHY" and any(.objectTypes[];.id==$type)' "${work_dir}/home.json" >/dev/null || fail "home did not expose authorized object types"

query=$(jq -cn --arg type "${employee_type}" '{objectTypeId:$type,where:{},sort:[],pageSize:25,columns:[]}')
curl -fsS -X POST "${api}/object-sets/query" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${query}" > "${work_dir}/page1.json"
jq -e --arg prefix "P08-${stamp}" '.items|map(select(.objectId|startswith($prefix)))|length>=25' "${work_dir}/page1.json" >/dev/null || fail "server-side Object Set did not return first stable page"
cursor=$(jq -er .nextCursor "${work_dir}/page1.json")
page2=$(printf '%s' "${query}" | jq -c --arg cursor "${cursor}" '.cursor=$cursor')
curl -fsS -X POST "${api}/object-sets/query" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${page2}" > "${work_dir}/page2.json"
first_ids=$(jq -r '.items[].objectId' "${work_dir}/page1.json")
second_ids=$(jq -r '.items[].objectId' "${work_dir}/page2.json")
[ -z "$(printf '%s\n%s\n' "${first_ids}" "${second_ids}" | sort | uniq -d)" ] || fail "cursor pages contained duplicate object ids"

filter_query=$(jq -cn --arg type "${employee_type}" --arg property "${name_property}" --arg prefix "P08 ${stamp} User 0" '{objectTypeId:$type,where:{type:"property",propertyId:$property,operator:"starts_with",value:$prefix},sort:[],pageSize:25,columns:[$property]}')
curl -fsS -X POST "${api}/object-sets/query" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${filter_query}" > "${work_dir}/filtered.json"
jq -e --arg prefix "P08 ${stamp} User 0" '.items|length==10 and all(.[];.title|startswith($prefix))' "${work_dir}/filtered.json" >/dev/null || fail "typed text filter was not compiled correctly"

facet_body=$(jq -cn --argjson query "${query}" --arg property "${department_property}" '{query:$query,propertyIds:[$property]}')
curl -fsS -X POST "${api}/object-sets/facets" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${facet_body}" > "${work_dir}/facets.json"
jq -e '.[0].buckets|any(.value=="Research" and .count>=15) and any(.value=="Operations" and .count>=15)' "${work_dir}/facets.json" >/dev/null || fail "permission-filtered facets were incorrect"

object_id="P08-${stamp}-00"
curl -fsS "${api}/objects/${employee_type}/${object_id}" -H "${auth_viewer}" > "${work_dir}/detail.json"
jq -e --arg email "${email_property}" --arg title "P08 ${stamp} User 00" '.title==$title and .properties.name==$title and (.properties|has("email")|not) and (.redactedFields|index($email)!=null)' "${work_dir}/detail.json" >/dev/null || fail "HugeGraph detail did not server-redact sensitive email"
curl -fsS -X POST "${api}/object-sets/compare" -H "${auth_viewer}" -H 'Content-Type: application/json' \
  -d "$(jq -cn --arg type "${employee_type}" --arg a "P08-${stamp}-00" --arg b "P08-${stamp}-01" '{objectTypeId:$type,objectIds:[$a,$b]}')" | jq -e --arg email "${email_property}" '(.differingProperties|index($email)==null)' >/dev/null || fail "compare leaked a sensitive field difference"

curl -fsS -X POST "${api}/objects/${employee_type}/E-P02-001/links" -H "${auth_viewer}" -H 'Content-Type: application/json' -d '{"direction":"BOTH","pageSize":25}' | jq -e '.items|any(.linkTypeName=="所属部门" and .targetObjectId=="D-P02-001")' >/dev/null || fail "HugeGraph one-hop relation was not returned"
curl -fsS "${api}/objects/${employee_type}/E-P02-001/activity" -H "${auth_viewer}" | jq -e 'length>0 and all(.[];has("summary") and (has("before")|not) and (has("after")|not))' >/dev/null || fail "safe activity evidence was incomplete"
curl -fsS "${api}/objects/${employee_type}/${object_id}/provenance" -H "${auth_viewer}" | jq -e '.indexStatus=="HEALTHY" and .ontologyRevision>0' >/dev/null || fail "object provenance was incomplete"

search_body=$(jq -cn --arg query "P08 ${stamp} User 00" '{query:$query,mode:"ALL",tab:"ALL",size:20}')
curl -fsS -X POST "${api}/search/objects" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${search_body}" | jq -e --arg title "P08 ${stamp} User 00" '.objects|any(.title==$title and (.properties|has("email")|not))' >/dev/null || fail "global search or snippet security failed"

exploration_body=$(jq -cn --arg type "${employee_type}" --argjson query "${filter_query}" --arg name "P08 动态探索 ${stamp}" '{name:$name,description:"queries current data",objectTypeId:$type,query:$query,columns:[],perspective:"TABLE",viewConfig:{},visibility:"PRIVATE"}')
curl -fsS -X POST "${api}/explorations" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${exploration_body}" > "${work_dir}/exploration.json"
exploration_id=$(jq -er .id "${work_dir}/exploration.json")
curl -fsS -X POST "${api}/explorations/${exploration_id}/share" -H "${auth_viewer}" | jq -e '.visibility=="SHARED"' >/dev/null || fail "exploration sharing failed"

list_body=$(jq -cn --arg type "${employee_type}" --arg name "P08 静态清单 ${stamp}" --arg a "P08-${stamp}-00" --arg b "P08-${stamp}-01" '{name:$name,description:"references only",objectTypeId:$type,visibility:"PRIVATE",objectIds:[$a,$b]}')
curl -fsS -X POST "${api}/object-lists" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${list_body}" | jq -e '.itemCount==2' >/dev/null || fail "static object list did not persist references"

selection_body=$(jq -cn --argjson query "${query}" --arg a "P08-${stamp}-00" --arg b "P08-${stamp}-01" '{query:$query,objectIds:[$a,$b]}')
curl -fsS -X POST "${api}/object-sets/selection-tokens" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${selection_body}" > "${work_dir}/selection.json"
jq -e '.objectCount==2 and (.token|length>40)' "${work_dir}/selection.json" >/dev/null || fail "selection token was not frozen"

export_body=$(jq -cn --argjson query "${query}" --arg a "P08-${stamp}-00" --arg b "P08-${stamp}-01" --arg name "${name_property}" '{query:$query,objectIds:[$a,$b],columns:[$name],format:"CSV"}')
curl -fsS -X POST "${api}/export-jobs" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${export_body}" > "${work_dir}/export.json"
export_id=$(jq -er .id "${work_dir}/export.json")
jq -e '.status=="SUCCEEDED" and .rowCount==2 and (.contentHash|length==64)' "${work_dir}/export.json" >/dev/null || fail "MinIO export job did not complete"
curl -fsS "${api}/export-jobs/${export_id}/download" -H "${auth_viewer}" > "${work_dir}/export.csv"
grep -q "P08 ${stamp} User 00" "${work_dir}/export.csv" || fail "export did not contain authorized data"
if grep -q 'secret.invalid' "${work_dir}/export.csv"; then fail "export leaked sensitive email"; fi

action_id=88000000-0000-0000-0000-000000000801
action_version_id=88000000-0000-0000-0000-000000000802
sql "INSERT INTO control.ontology_resources(id,kind,api_name,display_name,description,physical_key,owner_id,owner_name,maturity,promoted,tags,latest_version,active_version,published_revision) VALUES ('${action_id}','ACTION','P08ReviewEmployee','审核员工','P08 E2E published Action','p08:action:review-employee','p08-e2e','P08 E2E','ACTIVE',false,'{}',1,1,${revision}) ON CONFLICT (id) DO UPDATE SET active_version=1,published_revision=${revision},tombstoned=false; INSERT INTO control.ontology_resource_versions(id,resource_id,version,lifecycle,display_name,description,maturity,promoted,owner_id,owner_name,tags,definition,fingerprint,created_by,created_by_name,published_revision,published_at) VALUES ('${action_version_id}','${action_id}',1,'PUBLISHED','审核员工','P08 E2E published Action','ACTIVE',false,'p08-e2e','P08 E2E','{}','{}',repeat('8',64),'p08-e2e','P08 E2E',${revision},now()) ON CONFLICT (id) DO NOTHING; INSERT INTO control.action_types(resource_id,target_object_type_id) VALUES ('${action_id}','${employee_type}') ON CONFLICT (resource_id) DO UPDATE SET target_object_type_id=excluded.target_object_type_id; INSERT INTO control.action_type_versions(version_id,resource_id,operation,approval_policy,rules,submit_condition) VALUES ('${action_version_id}','${action_id}','UPDATE','NONE','[]','{}') ON CONFLICT (version_id) DO NOTHING;" >/dev/null
bulk_type=${employee_type}
bulk_object=${object_id}
bulk_query=$(jq -cn --arg type "${bulk_type}" '{objectTypeId:$type,where:{},sort:[],pageSize:25,columns:[]}')
bulk_selection=$(curl -fsS -X POST "${api}/object-sets/selection-tokens" -H "${auth_builder}" -H 'Content-Type: application/json' -d "$(jq -cn --argjson query "${bulk_query}" --arg object "${bulk_object}" '{query:$query,objectIds:[$object]}')" | jq -er .token)
bulk_body=$(jq -cn --arg action "${action_id}" --arg token "${bulk_selection}" '{actionId:$action,selectionToken:$token,parameters:{value:"P08 preview"}}')
viewer_code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${api}/bulk-action-jobs" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${bulk_body}")
[ "${viewer_code}" = 403 ] || fail "Viewer could start a bulk Action"
curl -fsS -X POST "${api}/bulk-action-jobs" -H "${auth_builder}" -H 'Content-Type: application/json' -d "${bulk_body}" | jq -e '.status=="SUCCEEDED" and .totalCount==1' >/dev/null || fail "Builder bulk Action batching gate failed"

${compose} --profile '*' exec -T ontology-core curl -fsS -X PUT 'http://opensearch:9200/platform-ontology-objects/_doc/p08-restricted?refresh=true' -H 'Content-Type: application/json' -d "{\"graph_element_id\":\"restricted\",\"object_type\":\"Employee\",\"object_id\":\"P08-RESTRICTED-${stamp}\",\"ontology_revision\":${revision},\"entity_version\":1,\"correlation_id\":\"p08-restricted\",\"occurred_at\":\"2026-07-20T00:00:00Z\",\"visibility_tokens\":[\"role:Admin\"],\"properties\":{\"name\":\"P08 Restricted\"}}" >/dev/null
restricted_search='{"query":"P08 Restricted","mode":"ALL","tab":"OBJECTS","size":20}'
curl -fsS -X POST "${api}/search/objects" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${restricted_search}" | jq -e '.objects|length==0' >/dev/null || fail "Viewer inferred an unauthorized object"
curl -fsS -X POST "${api}/search/objects" -H "${auth_admin}" -H 'Content-Type: application/json' -d "${restricted_search}" | jq -e '.objects|length==1' >/dev/null || fail "Admin security token was not compiled into OpenSearch"

patch_code=$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH "${api}/objects/${employee_type}/${object_id}" -H "${auth_admin}" -H 'Content-Type: application/json' -d '{"name":"forbidden"}')
[ "${patch_code}" = 405 ] || fail "Explorer exposed a direct object CRUD path"
docker stop "${opensearch_container}" >/dev/null
curl -fsS "${api}/objects/${employee_type}/${object_id}" -H "${auth_viewer}" | jq -e --arg title "P08 ${stamp} User 00" '.title==$title' >/dev/null || fail "HugeGraph detail did not degrade independently"
search_code=$(curl -sS -o "${work_dir}/degraded.json" -w '%{http_code}' -X POST "${api}/search/objects" -H "${auth_viewer}" -H 'Content-Type: application/json' -d "${search_body}")
[ "${search_code}" = 503 ] || fail "search disguised an OpenSearch outage"
docker start "${opensearch_container}" >/dev/null
for _attempt in $(seq 1 30); do
  ${compose} --profile '*' exec -T ontology-core curl -fsS 'http://opensearch:9200/_cluster/health' >/dev/null 2>&1 && break
  sleep 1
done

body_columns=$(sql "SELECT count(*) FROM information_schema.columns WHERE table_schema='control' AND table_name IN ('saved_explorations','saved_exploration_versions','object_lists','object_list_items','selection_tokens','selection_token_items','export_jobs','bulk_action_jobs','bulk_action_items') AND column_name IN ('payload','properties','object_body','object_json')")
[ "${body_columns}" = 0 ] || fail "PostgreSQL explorer tables contain object body columns"
audit_count=$(sql "SELECT count(*) FROM control.audit_events WHERE resource_type IN ('EXPLORATION','OBJECT_LIST','EXPORT_JOB','BULK_ACTION_JOB') AND actor_id IN ('${viewer_subject}','${builder_subject}')")
[ "${audit_count}" -ge 4 ] || fail "explorer audit evidence was incomplete"

echo "E:explorer-page passed: storage-authorized search/Facet/cursor, HugeGraph detail/relation/degradation, redaction, compare, saved references, signed selection, MinIO export, Action gate, RBAC and audit verified."
