#!/bin/bash
set -euo pipefail

admin=(/pulsar/bin/pulsar-admin --admin-url "${PULSAR_ADMIN_URL}")

"${admin[@]}" tenants create platform --allowed-clusters standalone 2>/dev/null || \
  "${admin[@]}" tenants update platform --allowed-clusters standalone

for namespace in commands dlq index ingestion quality system; do
  "${admin[@]}" namespaces create "platform/${namespace}" 2>/dev/null || true
  "${admin[@]}" namespaces set-retention "platform/${namespace}" --size 10G --time 7d
done

declare -A topics=(
  [commands/mutation-batches]=6
  [dlq/projection-events]=3
  [index/rebuild-events]=3
  [ingestion/dataset-events]=6
  [ingestion/object-events]=12
  [ingestion/relation-events]=12
  [quality/quarantine]=3
  [system/audit-events]=6
)

for topic in "${!topics[@]}"; do
  "${admin[@]}" topics create-partitioned-topic \
    "persistent://platform/${topic}" --partitions "${topics[$topic]}" 2>/dev/null || true
done
