#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/docker/scripts/compose.sh"
api=http://localhost:9080/api/ontology/v1/modeling
work_dir=$(mktemp -d)
opensearch_container=$(${compose} --profile '*' ps -q opensearch)
trap 'docker start "${opensearch_container}" >/dev/null 2>&1 || true; rm -rf "${work_dir}"' EXIT INT TERM

fail() {
  echo "E:modeling-page failed: $1" >&2
  exit 1
}

sql() {
  "${compose}" --profile '*' exec -T postgres psql -U ontology -d ontology -Atc "$1"
}

wait_deployment() {
  deployment_id=$1
  for _attempt in $(seq 1 60); do
    curl -fsS "${api}/deployments/${deployment_id}" -H "Authorization: Bearer ${admin_api_token}" > "${work_dir}/deployment.json"
    status=$(jq -r .status "${work_dir}/deployment.json")
    if [ "${status}" = SUCCEEDED ] || [ "${status}" = FAILED ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

admin_api_token=local
builder_api_token=local
viewer_api_token=local

for service in apisix frontend hugegraph ontology-core opensearch postgres projection-worker pulsar; do
  state=$("${compose}" --profile '*' ps --format json "${service}" | jq -r 'if type == "array" then .[0].State else .State end')
  [ "${state}" = running ] || fail "${service} is not running"
done

baseline=$(curl -fsS "${api}/summary" -H "Authorization: Bearer ${viewer_api_token}" | jq -er .ontologyRevision)
stamp=$(date +%s)
object_api="P07Asset${stamp}"
object_body=$(jq -cn --arg api "${object_api}" '{displayName:"P07 资产",apiName:$api,description:"P07 canonical object type",maturity:"EXPERIMENTAL",ownerId:"p07",ownerName:"P07 Builder",tags:["e2e","p07"],promoted:false,sourceMode:"ACTION",properties:[{apiName:"asset_id",displayName:"资产编号",description:"Stable identity",valueType:"STRING",required:true,primaryKey:true,titleProperty:false,searchable:true,filterable:true,sortable:true,sensitive:false},{apiName:"name",displayName:"名称",description:"Display title",valueType:"STRING",required:true,primaryKey:false,titleProperty:true,searchable:true,filterable:true,sortable:true,sensitive:false},{apiName:"secret_note",displayName:"敏感备注",description:"Must not be indexed",valueType:"STRING",required:false,primaryKey:false,titleProperty:false,searchable:true,filterable:false,sortable:false,sensitive:true}]}')
curl -fsS -X POST "${api}/object-types" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' \
  -d "${object_body}" > "${work_dir}/object.json"
object_id=$(jq -er .id "${work_dir}/object.json")
object_physical=$(jq -er .physicalKey "${work_dir}/object.json")
id_physical=$(jq -er '.properties[] | select(.apiName=="asset_id") | .physicalKey' "${work_dir}/object.json")
name_physical=$(jq -er '.properties[] | select(.apiName=="name") | .physicalKey' "${work_dir}/object.json")
secret_physical=$(jq -er '.properties[] | select(.apiName=="secret_note") | .physicalKey' "${work_dir}/object.json")
jq -e '.lifecycle=="DRAFT" and (.activeVersion==null) and (.properties[] | select(.apiName=="secret_note") | .searchable==false)' "${work_dir}/object.json" >/dev/null \
  || fail "object draft lifecycle or sensitive-index default is wrong"
[ "$(sql "SELECT max(revision) FROM control.ontology_revisions WHERE status='ACTIVE'")" = "${baseline}" ] || fail "creating a draft deployed a revision"

invalid_body=$(jq -cn --arg api "InvalidJson${stamp}" '{displayName:"Invalid JSON",apiName:$api,sourceMode:"ACTION",properties:[{apiName:"payload",displayName:"Payload",valueType:"JSON",required:true,primaryKey:true,titleProperty:true,searchable:false,filterable:false,sortable:false,sensitive:false}]}')
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${api}/object-types" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d "${invalid_body}")
[ "${code}" = 422 ] || fail "invalid JSON primary key returned ${code}"
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${api}/object-types" -H "Authorization: Bearer ${viewer_api_token}" -H 'Content-Type: application/json' -d "${object_body}")
[ "${code}" = 403 ] || fail "Viewer draft creation returned ${code}"

employee_id=00000000-0000-0000-0000-000000000102
department_id=00000000-0000-0000-0000-000000000101
employee_name_property=20000000-0000-0000-0000-000000000202

interface_body=$(jq -cn --arg api "NamedEntity${stamp}" --arg object "${object_id}" --arg property "${name_physical}" \
  '{displayName:"命名实体",apiName:$api,description:"Cross-type name slot",slots:[{apiName:"name",displayName:"名称",valueType:"STRING",required:true}],implementations:[]}')
curl -fsS -X POST "${api}/interfaces" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d "${interface_body}" > "${work_dir}/interface.json"
interface_id=$(jq -er .id "${work_dir}/interface.json")

link_body=$(jq -cn --arg api "owns_asset_${stamp}" --arg left "${employee_id}" --arg right "${object_id}" \
  '{displayName:"负责资产",apiName:$api,description:"Employee owns asset",leftObjectTypeId:$left,rightObjectTypeId:$right,cardinality:"1:N",sourceMode:"FOREIGN_KEY",leftDisplayName:"负责资产",rightDisplayName:"负责人"}')
curl -fsS -X POST "${api}/link-types" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d "${link_body}" > "${work_dir}/link.json"
link_id=$(jq -er .id "${work_dir}/link.json")

action_body=$(jq -cn --arg api "RenameAsset${stamp}" --arg target "${object_id}" --arg property "$(jq -er '.properties[] | select(.apiName=="name") | .id' "${work_dir}/object.json")" \
  '{displayName:"重命名资产",apiName:$api,description:"Declarative rename",targetObjectTypeId:$target,operation:"UPDATE",approvalPolicy:"ALWAYS",parameters:[{apiName:"value",displayName:"新名称",valueType:"STRING",required:true,sensitive:false}],rules:[{type:"SET_PROPERTY",targetPropertyId:$property,valueFrom:"value"}],submitCondition:{},maturity:"EXPERIMENTAL"}')
curl -fsS -X POST "${api}/actions" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d "${action_body}" > "${work_dir}/action.json"
action_id=$(jq -er .id "${work_dir}/action.json")
curl -fsS -X POST "${api}/actions/${action_id}/preview" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d '{"parameters":{"value":"新名称"},"objectId":"A-1","objectVersion":1}' \
  | jq -e '.previewToken and (.edits|length==1) and .diff.objectVersionChecked' >/dev/null || fail "Action preview did not compile an edit plan"

function_body=$(jq -cn --arg api "FindAssets${stamp}" --arg target "${object_id}" \
  '{displayName:"查找资产",apiName:$api,description:"Caller scoped read-only function",outputType:"TABLE",queryDsl:{fromObjectTypeId:$target,operation:"FILTER",limit:100},dependencyIds:[$target],timeoutMs:5000,maxResults:1000,cacheSeconds:60,parameters:[],maturity:"EXPERIMENTAL"}')
curl -fsS -X POST "${api}/functions" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d "${function_body}" > "${work_dir}/function.json"
function_id=$(jq -er .id "${work_dir}/function.json")
curl -fsS -X POST "${api}/functions/${function_id}/test" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d '{"inputs":{}}' \
  | jq -e '.callerPermissionsApplied and .versionBinding=="v1"' >/dev/null || fail "Function test did not preserve caller scope or exact version"

proposal_body=$(jq -cn --arg object "${object_id}" --arg link "${link_id}" --arg interface "${interface_id}" --arg action "${action_id}" --arg function "${function_id}" \
  '{title:"P07 multi-resource publish",description:"Object, link, interface, action and function",resourceIds:[$object,$link,$interface,$action,$function]}')
curl -fsS -X POST "${api}/proposals" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d "${proposal_body}" > "${work_dir}/proposal.json"
proposal_id=$(jq -er .id "${work_dir}/proposal.json")
curl -fsS -X POST "${api}/proposals/${proposal_id}/validate" -H "Authorization: Bearer ${builder_api_token}" | jq -e '.riskLevel=="LOW" and (.resources|length==5)' >/dev/null || fail "multi-resource validation failed"
curl -fsS -X POST "${api}/proposals/${proposal_id}/submit" -H "Authorization: Bearer ${builder_api_token}" | jq -e '.status=="IN_REVIEW"' >/dev/null || fail "proposal submit failed"
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${api}/proposals/${proposal_id}/reviews" -H "Authorization: Bearer ${builder_api_token}" -H 'Content-Type: application/json' -d '{"decision":"APPROVED","comment":"builder must not approve"}')
[ "${code}" = 403 ] || fail "Builder approval returned ${code}"
curl -fsS -X POST "${api}/proposals/${proposal_id}/reviews" -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d '{"decision":"APPROVED","comment":"P07 E2E approved"}' \
  | jq -e '.status=="APPROVED"' >/dev/null || fail "Admin approval failed"
curl -fsS -X POST "${api}/proposals/${proposal_id}/publish" -H "Authorization: Bearer ${admin_api_token}" > "${work_dir}/publish.json"
deployment_id=$(jq -er .id "${work_dir}/publish.json")
wait_deployment "${deployment_id}" || fail "successful deployment timed out"
jq -e '.status=="SUCCEEDED" and ([.steps[].status] | all(.=="SUCCEEDED"))' "${work_dir}/deployment.json" >/dev/null || fail "publish Saga did not complete every step"
revision=$(jq -er .targetRevision "${work_dir}/deployment.json")
[ "$(sql "SELECT max(revision) FROM control.ontology_revisions WHERE status='ACTIVE'")" = "${revision}" ] || fail "new revision was not atomically activated"
[ "$(sql "SELECT count(*) FROM control.object_properties WHERE revision=${revision} AND type_id='${object_physical}' AND sensitive AND NOT searchable")" = 1 ] || fail "immutable Projection Contract did not exclude sensitive search"

# A second proposal deliberately fails while OpenSearch is unavailable. The old revision must remain active.
failure_api="P07Recovery${stamp}"
failure_body=$(jq -cn --arg api "${failure_api}" '{displayName:"P07 恢复对象",apiName:$api,sourceMode:"ACTION",properties:[{apiName:"id",displayName:"标识",valueType:"STRING",required:true,primaryKey:true,titleProperty:true,searchable:true,filterable:true,sortable:true,sensitive:false}]}')
curl -fsS -X POST "${api}/object-types" -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d "${failure_body}" > "${work_dir}/failure-object.json"
failure_object_id=$(jq -er .id "${work_dir}/failure-object.json")
failure_proposal=$(curl -fsS -X POST "${api}/proposals" -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' \
  -d "$(jq -cn --arg id "${failure_object_id}" '{title:"P07 recovery rehearsal",resourceIds:[$id]}')" | jq -er .id)
curl -fsS -X POST "${api}/proposals/${failure_proposal}/submit" -H "Authorization: Bearer ${admin_api_token}" >/dev/null
curl -fsS -X POST "${api}/proposals/${failure_proposal}/reviews" -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d '{"decision":"APPROVED","comment":"recovery rehearsal"}' >/dev/null
docker stop "${opensearch_container}" >/dev/null
failure_deployment=$(curl -fsS -X POST "${api}/proposals/${failure_proposal}/publish" -H "Authorization: Bearer ${admin_api_token}" | jq -er .id)
wait_deployment "${failure_deployment}" || fail "failed deployment timed out"
jq -e '.status=="FAILED" and (.steps[] | select(.name=="MIGRATE_OPENSEARCH") | .status=="FAILED")' "${work_dir}/deployment.json" >/dev/null || fail "OpenSearch outage did not persist a failed Saga step"
[ "$(sql "SELECT max(revision) FROM control.ontology_revisions WHERE status='ACTIVE'")" = "${revision}" ] || fail "failed publish replaced the active revision"
docker start "${opensearch_container}" >/dev/null
for _attempt in $(seq 1 60); do
  if [ "$(docker inspect "${opensearch_container}" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')" = healthy ]; then break; fi
  sleep 1
done
retry_deployment=$(curl -fsS -X POST "${api}/proposals/${failure_proposal}/retry" -H "Authorization: Bearer ${admin_api_token}" | jq -er .id)
wait_deployment "${retry_deployment}" || fail "retry deployment timed out"
jq -e '.status=="SUCCEEDED"' "${work_dir}/deployment.json" >/dev/null || fail "deployment retry did not recover"
final_revision=$(jq -er .targetRevision "${work_dir}/deployment.json")

# Publish a real event using exact revision and stable contract keys.
event_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
event=$(jq -cn --arg event "${event_id}" --argjson revision "${final_revision}" --arg type "${object_physical}" --arg idKey "${id_physical}" --arg nameKey "${name_physical}" --arg secretKey "${secret_physical}" \
  '{event_id:$event,event_type:"object.upsert",schema_version:1,ontology_revision:$revision,occurred_at:"2026-07-20T14:00:00Z",producer:"e2e/p07",correlation_id:"p07-exact-contract",object_type:$type,object_id:"A-P07-001",object_version:1,payload:{($idKey):"A-P07-001",($nameKey):"可投影名称",($secretKey):"不得索引"},source:{data_source_id:"p07",asset_id:"p07",pipeline_run_id:"p07"}}')
printf '%s' "${event}" > "${work_dir}/event.json"
pulsar_container=$("${compose}" --profile '*' ps -q pulsar)
docker cp "${work_dir}/event.json" "${pulsar_container}:/tmp/p07-modeling-event.json" >/dev/null
"${compose}" --profile '*' exec -T pulsar bin/pulsar-client produce persistent://platform/ingestion/object-events --key "${object_physical}:A-P07-001" --files /tmp/p07-modeling-event.json >/dev/null 2>&1
for _attempt in $(seq 1 45); do
  [ "$(sql "SELECT status FROM control.projection_ledger WHERE event_id='${event_id}'")" = PROJECTED ] && break
  sleep 1
done
[ "$(sql "SELECT status FROM control.projection_ledger WHERE event_id='${event_id}'")" = PROJECTED ] || fail "Projection did not consume the exact published revision"
search_result=$("${compose}" --profile '*' exec -T opensearch curl -fsS "http://localhost:9200/platform-ontology-objects/_search?q=object_id:A-P07-001")
echo "${search_result}" | jq -e --arg name "${name_physical}" --arg secret "${secret_physical}" '.hits.total.value==1 and .hits.hits[0]._source.properties[$name]=="可投影名称" and (.hits.hits[0]._source.properties|has($secret)|not)' >/dev/null \
  || fail "Projection did not use physical contract keys or leaked a sensitive property"

audit_count=$(sql "SELECT count(*) FROM control.audit_events WHERE action IN ('ONTOLOGY_DRAFT_CREATED','ONTOLOGY_PROPOSAL_CREATED','ONTOLOGY_PROPOSAL_VALIDATED','ONTOLOGY_PROPOSAL_SUBMITTED','ONTOLOGY_PROPOSAL_APPROVED','ONTOLOGY_PUBLISH_REQUESTED','ONTOLOGY_PUBLISHED') AND occurred_at > now()-interval '1 hour'")
[ "${audit_count}" -ge 7 ] || fail "modeling audit evidence is incomplete"

trap - EXIT INT TERM
rm -rf "${work_dir}"
echo "E:modeling-page passed: canonical drafts, types, search roles, multi-resource review, real Saga failure/retry, exact revision projection, stable keys, sensitive filtering, and audit verified."
