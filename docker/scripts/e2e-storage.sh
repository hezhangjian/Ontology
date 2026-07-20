#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/docker/scripts/compose.sh"
fixtures="${project_root}/docker/e2e/fixtures"

pulsar_container=$(${compose} --profile '*' ps -q pulsar)
opensearch_container=$(${compose} --profile '*' ps -q opensearch)
worker_container=$(${compose} --profile '*' ps -q projection-worker)

cleanup() {
  docker start "${opensearch_container}" >/dev/null 2>&1 || true
  docker start "${worker_container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

sql() {
  ${compose} --profile '*' exec -T postgres \
    psql -U ontology -d ontology -Atc "$1"
}

publish() {
  fixture=$1
  topic=$2
  message_key=$3
  docker cp "${fixtures}/${fixture}.json" "${pulsar_container}:/tmp/${fixture}.json" >/dev/null
  ${compose} --profile '*' exec -T pulsar bin/pulsar-client produce \
    "${topic}" --key "${message_key}" --files "/tmp/${fixture}.json" >/dev/null 2>&1
}

wait_for_value() {
  query=$1
  expected=$2
  label=$3
  for _attempt in $(seq 1 45); do
    actual=$(sql "${query}")
    if [ "${actual}" = "${expected}" ]; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for ${label}; expected '${expected}', got '${actual}'." >&2
  return 1
}

for service in hugegraph ontology-core opensearch postgres projection-worker pulsar; do
  container_state=$(${compose} --profile '*' ps --format json "${service}" | jq -r 'if type == "array" then .[0].State else .State end')
  if [ "${container_state}" != "running" ]; then
    echo "${service} is not running." >&2
    exit 1
  fi
done

publish p02-employee-v1 persistent://platform/ingestion/object-events Employee:E-P02-001
wait_for_value \
  "SELECT status FROM control.projection_ledger WHERE event_id='11111111-1111-4111-8111-111111111111'" \
  PROJECTED "employee v1 projection"

publish p02-employee-v2 persistent://platform/ingestion/object-events Employee:E-P02-001
wait_for_value \
  "SELECT status FROM control.projection_ledger WHERE event_id='22222222-2222-4222-8222-222222222222'" \
  PROJECTED "employee v2 projection"

publish p02-employee-v1 persistent://platform/ingestion/object-events Employee:E-P02-001
publish p02-employee-stale persistent://platform/ingestion/object-events Employee:E-P02-001
wait_for_value \
  "SELECT status FROM control.projection_ledger WHERE event_id='33333333-3333-4333-8333-333333333333'" \
  STALE "stale event rejection"

if [ "$(sql "SELECT attempts FROM control.projection_ledger WHERE event_id='11111111-1111-4111-8111-111111111111'")" != 1 ]; then
  echo "Duplicate event changed the projection attempt count." >&2
  exit 1
fi

publish p02-department persistent://platform/ingestion/object-events Department:D-P02-001
wait_for_value \
  "SELECT status FROM control.projection_ledger WHERE event_id='44444444-4444-4444-8444-444444444444'" \
  PROJECTED "department projection"
publish p02-relation persistent://platform/ingestion/relation-events member_of:R-P02-001
wait_for_value \
  "SELECT status FROM control.projection_ledger WHERE event_id='55555555-5555-4555-8555-555555555555'" \
  PROJECTED "relation projection"

publish p02-delete-create persistent://platform/ingestion/object-events Employee:E-P02-DELETE
wait_for_value \
  "SELECT status FROM control.projection_ledger WHERE event_id='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'" \
  PROJECTED "delete fixture creation"
publish p02-delete persistent://platform/ingestion/object-events Employee:E-P02-DELETE
wait_for_value \
  "SELECT status FROM control.projection_ledger WHERE event_id='cccccccc-cccc-4ccc-8ccc-cccccccccccc'" \
  PROJECTED "delete tombstone projection"

publish p02-mutation-atomic persistent://platform/commands/mutation-batches p02-mutation-atomic-001
wait_for_value \
  "SELECT status FROM control.projection_operations WHERE idempotency_key='p02-mutation-atomic-001'" \
  PROJECTED "atomic mutation batch"
publish p02-mutation-atomic persistent://platform/commands/mutation-batches p02-mutation-atomic-001
if [ "$(sql "SELECT count(*) FROM control.projection_ledger WHERE correlation_id='p02-mutation-atomic' AND status='PROJECTED' AND attempts=1")" != 3 ]; then
  echo "Atomic mutation batch did not remain idempotent." >&2
  exit 1
fi

object_label_id=$(${compose} --profile '*' exec -T hugegraph curl --compressed -fsS \
  http://localhost:8080/graphspaces/DEFAULT/graphs/hugegraph/schema/vertexlabels/ontology_object | jq -r .id)
employee_graph=$(${compose} --profile '*' exec -T hugegraph curl --compressed -fsS \
  "http://localhost:8080/graphspaces/DEFAULT/graphs/hugegraph/graph/vertices/%22${object_label_id}%3ARW1wbG95ZWUARS1QMDItMDAx%22?label=ontology_object")
echo "${employee_graph}" | jq -e '
  .properties.object_version == 2 and
  (.properties.payload_json | fromjson | .email == "ada.v2@example.invalid")
' >/dev/null

employee_search=$(${compose} --profile '*' exec -T opensearch curl -fsS \
  'http://localhost:9200/platform-ontology-objects/_search?q=object_id:E-P02-001')
echo "${employee_search}" | jq -e '
  .hits.total.value == 1 and
  .hits.hits[0]._source.entity_version == 2 and
  .hits.hits[0]._source.properties.department == "Research" and
  (.hits.hits[0]._source.properties | has("email") | not)
' >/dev/null

relation_search=$(${compose} --profile '*' exec -T opensearch curl -fsS \
  'http://localhost:9200/platform-ontology-relations/_search?q=relation_id:R-P02-001')
echo "${relation_search}" | jq -e '.hits.total.value == 1' >/dev/null

deleted_document_id=$(printf 'object:Employee:E-P02-DELETE' | openssl base64 -A | tr '+/' '-_' | tr -d '=')
deleted_graph_code=$(${compose} --profile '*' exec -T hugegraph curl --compressed -sS -o /dev/null -w '%{http_code}' \
  "http://localhost:8080/graphspaces/DEFAULT/graphs/hugegraph/graph/vertices/%22${object_label_id}%3ARW1wbG95ZWUARS1QMDItREVMRVRF%22?label=ontology_object")
deleted_search_code=$(${compose} --profile '*' exec -T opensearch curl -sS -o /dev/null -w '%{http_code}' \
  "http://localhost:9200/platform-ontology-objects/_doc/${deleted_document_id}")
if [ "${deleted_graph_code}" != 404 ] || [ "${deleted_search_code}" != 404 ]; then
  echo "Deleted object remains in a storage projection." >&2
  exit 1
fi

publish p02-rebuild persistent://platform/index/rebuild-events p02-rebuild
wait_for_value \
  "SELECT status FROM control.index_rebuild_jobs WHERE rebuild_id='88888888-8888-4888-8888-888888888888'" \
  SUCCEEDED "index rebuild"
rebuild_index=$(sql "SELECT target_index FROM control.index_rebuild_jobs WHERE rebuild_id='88888888-8888-4888-8888-888888888888'")
alias_index=$(${compose} --profile '*' exec -T opensearch curl -fsS \
  http://localhost:9200/_alias/platform-ontology-objects | jq -r 'keys[0]')
if [ "${rebuild_index}" != "${alias_index}" ]; then
  echo "Object alias does not reference the recorded rebuild index." >&2
  exit 1
fi

failure_count_before=$(sql "SELECT count(*) FROM control.projection_failures WHERE event_id IS NULL AND error_code='CONTRACT_INVALID'")
dlq_count_before=$(${compose} --profile '*' exec -T pulsar bin/pulsar-admin topics partitioned-stats \
  persistent://platform/dlq/projection-events --per-partition | jq -r .msgInCounter)
publish p02-invalid persistent://platform/ingestion/object-events Employee:E-P02-BAD
for _attempt in $(seq 1 30); do
  failure_count_after=$(sql "SELECT count(*) FROM control.projection_failures WHERE event_id IS NULL AND error_code='CONTRACT_INVALID'")
  dlq_count_after=$(${compose} --profile '*' exec -T pulsar bin/pulsar-admin topics partitioned-stats \
    persistent://platform/dlq/projection-events --per-partition | jq -r .msgInCounter)
  if [ "${failure_count_after}" -gt "${failure_count_before}" ] && \
     [ "${dlq_count_after}" -gt "${dlq_count_before}" ]; then
    break
  fi
  sleep 1
done
if [ "${failure_count_after}" -le "${failure_count_before}" ] || \
   [ "${dlq_count_after}" -le "${dlq_count_before}" ]; then
  echo "Invalid event did not reach the failure ledger and DLQ." >&2
  exit 1
fi

storage_outage=0
exhausted_state=$(sql "SELECT status FROM control.projection_ledger WHERE event_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'")
if [ -z "${exhausted_state}" ]; then
  docker stop "${opensearch_container}" >/dev/null
  storage_outage=1
  publish p02-retry persistent://platform/ingestion/object-events Employee:E-P02-RETRY
  wait_for_value \
    "SELECT status FROM control.projection_ledger WHERE event_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'" \
    DLQ "retry exhaustion"
fi
if [ "$(sql "SELECT count(*) >= 3 FROM control.projection_failures WHERE event_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' AND retryable")" != t ]; then
  echo "Retryable storage outage did not record every exhausted attempt." >&2
  exit 1
fi

recovery_state=$(sql "SELECT status FROM control.projection_ledger WHERE event_id='dddddddd-dddd-4ddd-8ddd-dddddddddddd'")
if [ -z "${recovery_state}" ]; then
  if [ "${storage_outage}" = 0 ]; then
    docker stop "${opensearch_container}" >/dev/null
  fi
  publish p02-retry-recovery persistent://platform/ingestion/object-events Employee:E-P02-RECOVERY
  wait_for_value \
    "SELECT status FROM control.projection_ledger WHERE event_id='dddddddd-dddd-4ddd-8ddd-dddddddddddd'" \
    DEGRADED "degraded search projection"
  docker stop "${worker_container}" >/dev/null
  docker start "${opensearch_container}" >/dev/null
  for _attempt in $(seq 1 45); do
    opensearch_state=$(docker inspect "${opensearch_container}" \
      --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
    if [ "${opensearch_state}" = healthy ] && \
       ${compose} --profile '*' exec -T opensearch curl -fsS \
         http://localhost:9200/platform-ontology-objects/_count >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  docker start "${worker_container}" >/dev/null
fi
wait_for_value \
  "SELECT status FROM control.projection_ledger WHERE event_id='dddddddd-dddd-4ddd-8ddd-dddddddddddd'" \
  PROJECTED "search projection recovery"
if [ "$(sql "SELECT attempts >= 2 FROM control.projection_ledger WHERE event_id='dddddddd-dddd-4ddd-8ddd-dddddddddddd'")" != t ]; then
  echo "Recovered projection was not retried." >&2
  exit 1
fi

body_table_count=$(sql "
  SELECT count(*) FROM information_schema.tables
  WHERE table_schema NOT IN ('information_schema', 'pg_catalog')
    AND table_name IN ('object_bodies', 'objects', 'ontology_objects')
  ")
if [ "${body_table_count}" != 0 ]; then
  echo "PostgreSQL contains a forbidden object body table." >&2
  exit 1
fi

trap - EXIT INT TERM
echo "E:storage-e2e passed: idempotency, ordering, transactions, tombstones, DLQ, recovery, rebuild, and storage ownership verified."
