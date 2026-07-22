#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/deploy/scripts/compose.sh"
api=http://localhost:9080/api/ontology/v1
work_dir=$(mktemp -d)
trap 'rm -rf "${work_dir}"' EXIT

fail() {
  echo "E:connections-page failed: $1" >&2
  exit 1
}

admin_api_token=local
viewer_api_token=local

"${compose}" --profile '*' run --rm --no-deps --entrypoint /bin/sh minio-bootstrap -ec '
  minio_user=$(cat /run/secrets/minio_root_user)
  minio_password=$(cat /run/secrets/minio_root_password)
  mc alias set platform http://minio:9000 "${minio_user}" "${minio_password}" >/dev/null
  printf "order_id,customer,total,created_at\n1001,Ada,42.50,2026-07-20T08:00:00Z\n1002,Lin,18.25,2026-07-20T09:00:00Z\n" | mc pipe platform/raw/p03-e2e/orders.csv >/dev/null
'

minio_user=$(tr -d '\r\n' < "${project_root}/deploy/secrets/examples/minio_root_user")
minio_password=$(tr -d '\r\n' < "${project_root}/deploy/secrets/examples/minio_root_password")
connection_name="P03 E2E MinIO $(date +%s)"
credential_name="P03 managed credential $(date +%s)"
config=$(jq -cn '{endpoint:"http://minio:9000",region:"us-east-1",bucket:"raw",prefix:"p03-e2e/",pathStyle:true,timeoutSeconds:15}')
credential=$(jq -cn --arg name "${credential_name}" --arg user "${minio_user}" --arg password "${minio_password}" '{mode:"MANAGED",name:$name,values:{accessKey:$user,secretKey:$password}}')
test_request=$(jq -cn --argjson config "${config}" --argjson credential "${credential}" '{type:"S3_CSV",config:$config,credential:$credential}')

code=$(curl -sS -o "${work_dir}/test.json" -w '%{http_code}' -X POST "${api}/data-sources/test" \
  -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d "${test_request}")
[ "${code}" = 200 ] || fail "transient connection test returned ${code}"
jq -e '.status == "HEALTHY" and .assetCount >= 2 and (.testToken | length > 20) and (.stages | length == 5)' "${work_dir}/test.json" >/dev/null || fail "test stages or assets were incomplete"
test_token=$(jq -er .testToken "${work_dir}/test.json")

create_request=$(jq -cn --arg name "${connection_name}" --arg token "${test_token}" --argjson config "${config}" --argjson credential "${credential}" \
  '{name:$name,description:"P03 automated MinIO connection",type:"S3_CSV",ownerId:"ontology-e2e-admin",ownerName:"P03 E2E Admin",tags:["e2e","p03"],config:$config,credential:$credential,testToken:$token}')
code=$(curl -sS -o "${work_dir}/created.json" -w '%{http_code}' -X POST "${api}/data-sources" \
  -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d "${create_request}")
[ "${code}" = 201 ] || fail "create returned ${code}"
source_id=$(jq -er .id "${work_dir}/created.json")
secret_id=$(jq -er .credential.id "${work_dir}/created.json")
jq -e '.status == "HEALTHY" and .assetCount >= 2 and .credential.provider == "MANAGED" and (.config.password? == null) and (.credential.ciphertext? == null)' "${work_dir}/created.json" >/dev/null || fail "create response leaked or omitted state"
if grep -Fq "${minio_password}" "${work_dir}/created.json"; then fail "credential appeared in create response"; fi

code=$(curl -sS -o "${work_dir}/duplicate.json" -w '%{http_code}' -X POST "${api}/data-sources" \
  -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d "${create_request}")
[ "${code}" = 409 ] || fail "case-insensitive unique name returned ${code}"

asset_id=$(curl -fsS "${api}/data-sources/${source_id}/assets?size=100" -H "Authorization: Bearer ${admin_api_token}" \
  | jq -er '.items[] | select(.fullPath == "raw/p03-e2e/orders.csv") | .id')
curl -fsS "${api}/data-sources/${source_id}/assets/${asset_id}" -H "Authorization: Bearer ${admin_api_token}" \
  | jq -e '.schemaStatus == "READY" and .fieldCount == 4 and (.fields | length == 4)' >/dev/null || fail "CSV schema inference was incomplete"
curl -fsS -X POST "${api}/data-sources/${source_id}/assets/${asset_id}/preview" \
  -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d '{"limit":50}' \
  | jq -e '.rows[0].order_id == "1001" and .maxBytes == 1048576' >/dev/null || fail "bounded CSV preview failed"

version=$(jq -er .version "${work_dir}/created.json")
curl -fsS -X PATCH "${api}/data-sources/${source_id}" -H "Authorization: Bearer ${admin_api_token}" \
  -H 'Content-Type: application/json' -H "If-Match: ${version}" -d '{"description":"P03 optimistic update verified"}' \
  | jq -e '.description == "P03 optimistic update verified" and .version == 2' >/dev/null || fail "optimistic update failed"
code=$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH "${api}/data-sources/${source_id}" \
  -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -H "If-Match: ${version}" -d '{"description":"stale"}')
[ "${code}" = 409 ] || fail "stale optimistic update returned ${code}"

code=$(curl -sS -o /dev/null -w '%{http_code}' "${api}/data-sources" -H "Authorization: Bearer ${viewer_api_token}")
[ "${code}" = 403 ] || fail "Viewer list access returned ${code}"

curl -fsS -X POST "${api}/data-sources/${source_id}/disable" -H "Authorization: Bearer ${admin_api_token}" \
  | jq -e '.status == "DISABLED"' >/dev/null || fail "disable failed"
for blocked in "discover" "assets/${asset_id}/preview"; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${api}/data-sources/${source_id}/${blocked}" \
    -H "Authorization: Bearer ${admin_api_token}" -H 'Content-Type: application/json' -d '{"limit":50}')
  [ "${code}" = 409 ] || fail "disabled operation ${blocked} returned ${code}"
done
curl -fsS -X POST "${api}/data-sources/${source_id}/enable" -H "Authorization: Bearer ${admin_api_token}" \
  | jq -e '.status == "UNTESTED"' >/dev/null || fail "enable did not require retest"
curl -fsS -X POST "${api}/data-sources/${source_id}/test" -H "Authorization: Bearer ${admin_api_token}" \
  | jq -e '.status == "HEALTHY"' >/dev/null || fail "saved connection retest failed"

secret_state=$("${compose}" --profile '*' exec -T postgres psql -U ontology -d ontology -Atc \
  "SELECT (ciphertext IS NOT NULL AND octet_length(nonce)=12 AND algorithm='AES-256-GCM')::text FROM control.connection_secrets WHERE id='${secret_id}'")
[ "${secret_state}" = true ] || fail "managed credential was not encrypted with AES-256-GCM"
orphan_count=$("${compose}" --profile '*' exec -T postgres psql -U ontology -d ontology -Atc \
  "SELECT count(*) FROM control.connection_secrets WHERE name='${credential_name}'")
[ "${orphan_count}" = 1 ] || fail "failed duplicate creation left an orphan credential"

curl -fsS -X POST "${api}/data-sources/${source_id}/disable" -H "Authorization: Bearer ${admin_api_token}" >/dev/null
code=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE "${api}/data-sources/${source_id}" -H "Authorization: Bearer ${admin_api_token}")
[ "${code}" = 204 ] || fail "eligible Admin delete returned ${code}"
code=$(curl -sS -o /dev/null -w '%{http_code}' "${api}/data-sources/${source_id}" -H "Authorization: Bearer ${admin_api_token}")
[ "${code}" = 404 ] || fail "deleted connection remained readable"
audit_count=$("${compose}" --profile '*' exec -T postgres psql -U ontology -d ontology -Atc \
  "SELECT count(*) FROM control.audit_events WHERE resource_id='${source_id}' AND action IN ('DATA_SOURCE_CREATED','DATA_SOURCE_UPDATED','DATA_SOURCE_TESTED','DATA_SOURCE_DISABLED','DATA_SOURCE_ENABLED','DATA_SOURCE_DELETED')")
[ "${audit_count}" -ge 6 ] || fail "expected lifecycle audit events were missing"
if "${compose}" --profile '*' logs --no-color ontology-core | grep -Fq "${minio_password}"; then fail "credential appeared in service logs"; fi

echo "E:connections-page passed: encryption, testing, discovery, schema, preview, optimistic locking, roles, lifecycle, audit, and secret hygiene verified."
