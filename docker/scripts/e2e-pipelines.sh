#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/docker/scripts/compose.sh"
api=http://localhost:9080/api/ontology/v1
keycloak=http://localhost:8083
work_dir=$(mktemp -d)
trap 'rm -rf "${work_dir}"' EXIT

fail() {
  echo "E:pipelines-page failed: $1" >&2
  exit 1
}

sql() {
  "${compose}" --profile '*' exec -T postgres psql -U ontology -d ontology -Atc "$1"
}

wait_for_json() {
  url=$1
  expression=$2
  output=$3
  for _attempt in $(seq 1 90); do
    curl -fsS "${url}" -H "Authorization: Bearer ${admin_api_token}" > "${output}"
    if jq -e "${expression}" "${output}" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

admin_password=$(tr -d '\r\n' < "${project_root}/docker/secrets/examples/keycloak_admin_password")
admin_token=$(curl -fsS -X POST "${keycloak}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
  --data-urlencode username=admin --data-urlencode "password=${admin_password}" | jq -er .access_token)

service_token() {
  client_id=$1
  roles=$2
  client_secret=$(openssl rand -hex 24)
  client_uuid=$(curl -fsS "${keycloak}/admin/realms/ontology/clients?clientId=${client_id}" \
    -H "Authorization: Bearer ${admin_token}" | jq -r '.[0].id // empty')
  client_json=$(jq -n --arg id "${client_id}" --arg secret "${client_secret}" \
    '{clientId:$id,enabled:true,protocol:"openid-connect",publicClient:false,clientAuthenticatorType:"client-secret",secret:$secret,serviceAccountsEnabled:true,standardFlowEnabled:false,directAccessGrantsEnabled:false}')
  if [ -z "${client_uuid}" ]; then
    curl -fsS -X POST "${keycloak}/admin/realms/ontology/clients" -H "Authorization: Bearer ${admin_token}" \
      -H 'Content-Type: application/json' -d "${client_json}" >/dev/null
    client_uuid=$(curl -fsS "${keycloak}/admin/realms/ontology/clients?clientId=${client_id}" \
      -H "Authorization: Bearer ${admin_token}" | jq -er '.[0].id')
  else
    curl -fsS -X PUT "${keycloak}/admin/realms/ontology/clients/${client_uuid}" -H "Authorization: Bearer ${admin_token}" \
      -H 'Content-Type: application/json' -d "${client_json}" >/dev/null
  fi
  service_user=$(curl -fsS "${keycloak}/admin/realms/ontology/clients/${client_uuid}/service-account-user" \
    -H "Authorization: Bearer ${admin_token}" | jq -er .id)
  role_json='[]'
  old_ifs=$IFS
  IFS=,
  for role in ${roles}; do
    role_value=$(curl -fsS "${keycloak}/admin/realms/ontology/roles/${role}" -H "Authorization: Bearer ${admin_token}")
    role_json=$(jq -c --argjson role "${role_value}" '. + [$role]' <<EOF
${role_json}
EOF
)
  done
  IFS=$old_ifs
  curl -fsS -X POST "${keycloak}/admin/realms/ontology/users/${service_user}/role-mappings/realm" \
    -H "Authorization: Bearer ${admin_token}" -H 'Content-Type: application/json' -d "${role_json}" >/dev/null
  curl -fsS -X POST "${keycloak}/realms/ontology/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode grant_type=client_credentials \
    --data-urlencode "client_id=${client_id}" --data-urlencode "client_secret=${client_secret}" | jq -er .access_token
}

admin_api_token=$(service_token ontology-p04-e2e-admin Admin,Builder)
viewer_api_token=$(service_token ontology-p04-e2e-viewer Viewer)

for service in apisix flink-jobmanager flink-taskmanager frontend hugegraph keycloak minio ontology-core opensearch postgres projection-worker pulsar; do
  state=$("${compose}" --profile '*' ps --format json "${service}" | jq -r 'if type == "array" then .[0].State else .State end')
  [ "${state}" = running ] || fail "${service} is not running"
done

"${compose}" --profile '*' run --rm --no-deps --entrypoint /bin/sh minio-bootstrap -ec '
  minio_user=$(cat /run/secrets/minio_root_user)
  minio_password=$(cat /run/secrets/minio_root_password)
  mc alias set platform http://minio:9000 "${minio_user}" "${minio_password}" >/dev/null
  printf "employee_id,name,department,email\nE-P04-001,Ada,Research,ada.p04@example.invalid\nE-P04-002,Lin,Platform,lin.p04@example.invalid\n" | mc pipe platform/raw/p04-e2e/employees.csv >/dev/null
'

minio_user=$(tr -d '\r\n' < "${project_root}/docker/secrets/examples/minio_root_user")
minio_password=$(tr -d '\r\n' < "${project_root}/docker/secrets/examples/minio_root_password")
stamp=$(date +%s)
connection_name="P04 E2E MinIO ${stamp}"
config=$(jq -cn '{endpoint:"http://minio:9000",region:"us-east-1",bucket:"raw",prefix:"p04-e2e/",pathStyle:true,timeoutSeconds:15}')
credential=$(jq -cn --arg user "${minio_user}" --arg password "${minio_password}" \
  '{mode:"MANAGED",name:"P04 managed runtime credential",values:{accessKey:$user,secretKey:$password}}')
test_request=$(jq -cn --argjson config "${config}" --argjson credential "${credential}" \
  '{type:"S3_CSV",config:$config,credential:$credential}')
curl -fsS -X POST "${api}/data-sources/test" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -d "${test_request}" > "${work_dir}/connection-test.json"
test_token=$(jq -er .testToken "${work_dir}/connection-test.json")
create_source=$(jq -cn --arg name "${connection_name}" --arg token "${test_token}" --argjson config "${config}" --argjson credential "${credential}" \
  '{name:$name,description:"P04 Flink pipeline source",type:"S3_CSV",ownerId:"ontology-p04-e2e-admin",ownerName:"P04 E2E Admin",tags:["e2e","p04"],config:$config,credential:$credential,testToken:$token}')
curl -fsS -X POST "${api}/data-sources" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -d "${create_source}" > "${work_dir}/source.json"
source_id=$(jq -er .id "${work_dir}/source.json")
asset_id=$(curl -fsS "${api}/data-sources/${source_id}/assets?size=100" -H "Authorization: Bearer ${admin_api_token}" \
  | jq -er '.items[] | select(.fullPath == "raw/p04-e2e/employees.csv") | .id')

pipeline_name="P04 E2E Employees ${stamp}"
create_pipeline=$(jq -cn --arg name "${pipeline_name}" --arg source "${source_id}" --arg asset "${asset_id}" \
  '{name:$name,description:"P04 real Flink CSV projection",template:"FILE_BATCH",mode:"BATCH",dataSourceId:$source,sourceAssetId:$asset,ownerId:"ontology-p04-e2e-admin",ownerName:"P04 E2E Admin"}')
curl -fsS -X POST "${api}/pipelines" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -d "${create_pipeline}" > "${work_dir}/pipeline.json"
pipeline_id=$(jq -er .id "${work_dir}/pipeline.json")
etag=$(jq -er .draft.etag "${work_dir}/pipeline.json")

graph=$(jq -c '.draft.graph
  | .nodes |= map(
      if .id == "select-1" then .config.fields = [
        {source:"employee_id",target:"employee_id"},
        {source:"name",target:"name"},
        {source:"department",target:"department"},
        {source:"email",target:"email"}
      ]
      elif .id == "output-1" then .config = {
        objectTypeId:"Employee",idField:"employee_id",
        mappings:{name:"name",department:"department",email:"email"}
      }
      else . end)' "${work_dir}/pipeline.json")
patch_body=$(jq -cn --argjson graph "${graph}" '{graph:$graph}')
curl -fsS -X PATCH "${api}/pipelines/${pipeline_id}/draft" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -H "If-Match: ${etag}" -d "${patch_body}" > "${work_dir}/patched.json"
patched_etag=$(jq -er .draft.etag "${work_dir}/patched.json")
code=$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH "${api}/pipelines/${pipeline_id}/draft" \
  -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -H "If-Match: ${etag}" -d "${patch_body}")
[ "${code}" = 409 ] || fail "stale draft ETag returned ${code}"

curl -fsS -X POST "${api}/pipelines/${pipeline_id}/validate" -H "Authorization: Bearer ${admin_api_token}" \
  | tee "${work_dir}/validation.json" | jq -e '.valid and (.normalizedGraph.nodes[] | select(.id == "output-1") | .inputSchema | length == 4)' >/dev/null \
  || fail "schema propagation or graph validation failed"

curl -fsS -X POST "${api}/pipelines/${pipeline_id}/preview" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -d '{"nodeId":"output-1","limit":1}' > "${work_dir}/preview-submit.json"
preview_id=$(jq -er .id "${work_dir}/preview-submit.json")
if ! wait_for_json "${api}/pipeline-previews/${preview_id}" '.status == "COMPLETED" or .status == "FAILED"' "${work_dir}/preview.json"; then
  fail "bounded Flink preview timed out"
fi
jq -e '.status == "COMPLETED" and (.rows | length == 1) and .rows[0].payload.name == "Ada" and .rows[0].payload.email == "••••••"' \
  "${work_dir}/preview.json" >/dev/null || fail "preview did not use Flink limit, output semantics, or masking"

curl -fsS -X POST "${api}/pipelines/${pipeline_id}/publish" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -d '{"acknowledgeWarnings":true,"startAfterPublish":false}' > "${work_dir}/version-v1.json"
jq -e '.version == 1 and .jobSpec.engine == "FLINK" and .pipelineIr.api_version == "ontology.pipeline/v1"' \
  "${work_dir}/version-v1.json" >/dev/null || fail "immutable Flink version was not compiled"

curl -fsS -X POST "${api}/pipelines/${pipeline_id}/run" -H "Authorization: Bearer ${admin_api_token}" > "${work_dir}/run-submit.json"
run_id=$(jq -er .id "${work_dir}/run-submit.json")
if ! wait_for_json "${api}/pipeline-runs/${run_id}" '.run.status == "COMPLETED" or .run.status == "DEGRADED" or .run.status == "FAILED"' "${work_dir}/run.json"; then
  fail "formal Flink run timed out"
fi
jq -e '.run.status == "COMPLETED" and .run.pipelineVersion == 1 and .run.writtenCount == 2 and .run.projectionStatus == "COMPLETED"' \
  "${work_dir}/run.json" >/dev/null || fail "formal run did not complete after Projection acknowledgement"
[ "$(sql "SELECT count(*) FROM control.projection_ledger WHERE correlation_id='pipeline:${pipeline_id}:run:${run_id}' AND status='PROJECTED'")" = 2 ] \
  || fail "Projection ledger did not acknowledge both Flink events"

curl -sS -N --max-time 5 "${api}/pipeline-runs/${run_id}/events/stream?afterSequence=0" \
  -H "Authorization: Bearer ${admin_api_token}" > "${work_dir}/events.sse" || true
grep -q 'pipeline-run-terminal' "${work_dir}/events.sse" || fail "SSE reconnect did not replay durable state"
[ "$(sql "SELECT count(*) FROM control.pipeline_runs WHERE id='${run_id}'")" = 1 ] || fail "SSE reconnect resubmitted the run"

draft=$(curl -fsS "${api}/pipelines/${pipeline_id}" -H "Authorization: Bearer ${admin_api_token}")
draft_etag=$(jq -er .draft.etag <<EOF
${draft}
EOF
)
renamed_graph=$(jq -c '.draft.graph | .nodes |= map(if .id == "select-1" then .name = "选择员工字段 v2" else . end)' <<EOF
${draft}
EOF
)
rename_body=$(jq -cn --argjson graph "${renamed_graph}" '{graph:$graph}')
curl -fsS -X PATCH "${api}/pipelines/${pipeline_id}/draft" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -H "If-Match: ${draft_etag}" -d "${rename_body}" > "${work_dir}/draft-v2.json"
curl -fsS "${api}/pipelines/${pipeline_id}/versions/1" -H "Authorization: Bearer ${admin_api_token}" \
  | jq -e '.graph.nodes[] | select(.id == "select-1") | .name == "选择字段"' >/dev/null \
  || fail "editing a draft mutated immutable version v1"

curl -fsS -X POST "${api}/pipelines/${pipeline_id}/proposals" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -d '{"title":"P04 v2 review","summary":"Review evidence and downstream impact"}' > "${work_dir}/proposal.json"
proposal_id=$(jq -er .id "${work_dir}/proposal.json")
curl -fsS -X POST "${api}/pipelines/${pipeline_id}/proposals/${proposal_id}/approve" \
  -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d '{"comment":"Approved by P04 E2E"}' \
  | jq -e '.status == "APPROVED"' >/dev/null || fail "proposal approval failed"
curl -fsS -X POST "${api}/pipelines/${pipeline_id}/publish" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -d "$(jq -cn --arg id "${proposal_id}" '{acknowledgeWarnings:true,startAfterPublish:false,proposalId:$id}')" \
  | jq -e '.version == 2' >/dev/null || fail "approved draft did not publish as immutable v2"
curl -fsS -X POST "${api}/pipelines/${pipeline_id}/rollback" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -d '{"version":1,"acknowledgeDataNotReverted":true}' \
  | jq -e '.publishedVersion == 1' >/dev/null || fail "rollback did not activate v1 for future runs"

code=$(curl -sS -o /dev/null -w '%{http_code}' "${api}/pipelines" -H "Authorization: Bearer ${viewer_api_token}")
[ "${code}" = 403 ] || fail "Viewer pipeline access returned ${code}"

if sql "SELECT pipeline_ir::text || job_spec::text FROM control.pipeline_versions WHERE pipeline_id='${pipeline_id}'" | grep -Fq "${minio_password}"; then
  fail "credential appeared in Pipeline IR or Job Spec"
fi
if "${compose}" --profile '*' logs --no-color ontology-core flink-jobmanager flink-taskmanager | grep -Fq "${minio_password}"; then
  fail "credential appeared in Core or Flink logs"
fi
audit_count=$(sql "SELECT count(*) FROM control.audit_events WHERE resource_id='${pipeline_id}' AND action IN ('PIPELINE_CREATED','PIPELINE_DRAFT_UPDATED','PIPELINE_PREVIEW_SUBMITTED','PIPELINE_PUBLISHED','PIPELINE_RUN_CREATED','PIPELINE_PROPOSAL_SUBMITTED','PIPELINE_PROPOSAL_APPROVED','PIPELINE_ROLLED_BACK')")
[ "${audit_count}" -ge 8 ] || fail "pipeline lifecycle audit evidence is incomplete"

echo "E:pipelines-page passed: DAG/schema, ETag, real Flink preview and run, masking, immutable versions, proposals, rollback, SSE replay, Projection ack, roles, audit, and secret hygiene verified."
