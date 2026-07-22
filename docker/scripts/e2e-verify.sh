#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/docker/scripts/compose.sh"

"${project_root}/docker/scripts/healthcheck.sh"
"${project_root}/docker/scripts/compatibility-check.sh"

curl -fsS http://localhost:9080/healthz | grep -q '^ok$'
curl -fsS http://localhost:9080/data/connections | grep -q '<div id="root"></div>'

status=$(curl -sS -o /dev/null -w '%{http_code}' http://localhost:9080/api/ontology/actuator/health)
if [ "${status}" != 200 ]; then
  echo "Login-free gateway route returned ${status}, expected 200." >&2
  exit 1
fi

${compose} --profile '*' exec -T pulsar bin/pulsar-admin tenants get platform >/dev/null
for topic in \
  persistent://platform/commands/mutation-batches \
  persistent://platform/dlq/projection-events \
  persistent://platform/index/rebuild-events \
  persistent://platform/ingestion/object-events \
  persistent://platform/ingestion/relation-events \
  persistent://platform/quality/quarantine \
  persistent://platform/system/audit-events; do
  ${compose} --profile '*' exec -T pulsar bin/pulsar-admin topics partitioned-lookup "${topic}" >/dev/null
done

${compose} --profile '*' run --rm --no-deps --entrypoint /bin/sh minio-bootstrap -ec '
  minio_user=$(cat /run/secrets/minio_root_user)
  minio_password=$(cat /run/secrets/minio_root_password)
  mc alias set platform http://minio:9000 "${minio_user}" "${minio_password}" >/dev/null
  for bucket in aip-attachments backups exports flink-checkpoints import-staging quality-quarantine raw; do
    mc stat "platform/${bucket}" >/dev/null
  done
'

echo "E:platform-foundation passed. The portal is available without login."
