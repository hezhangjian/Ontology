# P02 projection and storage recovery

## Validate the projection foundation

Start the complete stack and run the source plus storage gates:

```bash
make compose-build
make compose-up
make verify-fast
make e2e-storage
```

`E:storage-e2e` publishes real Pulsar files and verifies version ordering, duplicate event IDs, a three-edit HugeGraph transaction, object tombstones, relation projection, sensitive-field filtering, permanent and retry-exhausted DLQ publication, OpenSearch outage recovery, alias-based rebuild, and the absence of PostgreSQL object-body tables.

P02 has no frontend surface, so it does not require an in-app browser test.

## Inspect projection state

Use the wrapper to keep Compose configuration consistent:

```bash
deploy/scripts/compose.sh --profile '*' logs --tail=200 projection-worker
deploy/scripts/compose.sh --profile '*' exec -T postgres \
  psql -U ontology -d ontology -c \
  "SELECT event_id, entity_key, entity_version, status, attempts, last_error_code FROM control.projection_ledger ORDER BY updated_at DESC LIMIT 50"
deploy/scripts/compose.sh --profile '*' exec -T postgres \
  psql -U ontology -d ontology -c \
  "SELECT error_code, retryable, attempt, safe_message, failed_at FROM control.projection_failures ORDER BY failed_at DESC LIMIT 50"
```

`GRAPH_APPLIED` or `DEGRADED` means the HugeGraph fact is durable but its search projection is not current. Restore OpenSearch and allow Pulsar redelivery; successful retry changes the event to `PROJECTED` without reapplying the graph write.

## DLQ handling

Inspect DLQ volume without printing payloads:

```bash
deploy/scripts/compose.sh --profile '*' exec -T pulsar \
  bin/pulsar-admin topics partitioned-stats \
  persistent://platform/dlq/projection-events --per-partition
```

Correct the originating contract or dependency before replay. The same `event_id` is terminal after `DLQ`; an authorized owning workflow must emit a corrected event with a new globally unique event ID and the appropriate entity version. P02 intentionally exposes no public replay endpoint; pipeline and administrative replay authorization belongs to later vertical slices.

## Rebuild OpenSearch

Publish an `IndexRebuildCommand` to `persistent://platform/index/rebuild-events`. The command must contain `rebuild_id`, `requested_at`, `requested_by`, and `correlation_id`. Repeating a successful `rebuild_id` is a no-op. Inspect the job and alias:

```bash
deploy/scripts/compose.sh --profile '*' exec -T postgres \
  psql -U ontology -d ontology -c \
  "SELECT rebuild_id, status, target_index, object_count, safe_error FROM control.index_rebuild_jobs ORDER BY requested_at DESC"
deploy/scripts/compose.sh --profile '*' exec -T opensearch \
  curl -fsS http://localhost:9200/_alias/platform-ontology-objects
```

Do not delete an old index until the job is `SUCCEEDED`, the alias points to `target_index`, and consumers have been verified. Never delete an unrelated index named outside the `platform-ontology-*` namespace.

## HugeGraph process health

The P02 launcher binds Gremlin only to the private Compose network for atomic mutation transactions and caps the HugeGraph JVM at 768 MiB for the single-host baseline. The declared HTTP health check validates `/versions`; when diagnosing the container, also confirm the Java child and private Gremlin endpoint:

```bash
deploy/scripts/compose.sh --profile '*' exec -T hugegraph ps -ef
deploy/scripts/compose.sh --profile '*' exec -T hugegraph \
  curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8182
```

An HTTP `400` from the empty Gremlin request confirms the endpoint is listening. Port 8182 must not be published on the host.
