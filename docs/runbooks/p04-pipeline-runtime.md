# P04 pipeline runtime operations

## Start and verify

From the repository root:

```sh
make compose-up
docker/scripts/healthcheck.sh
make e2e-pipelines
```

The E2E gate seeds a MinIO CSV, creates a managed connection and pipeline, runs a real bounded Flink preview, publishes immutable versions, executes a formal Flink job through Pulsar and Projection Worker, and verifies ETag conflicts, masking, SSE replay, rollback, roles, audit, and secret hygiene. It must end with `E:pipelines-page passed`.

Use `http://localhost:9080`, not the frontend container's `127.0.0.1:3000` debug port, for browser testing. The APISIX origin owns `/api/ontology/*`; the standalone Nginx port serves only static assets and intentionally has no API proxy.

## Diagnose a run

1. Open the run detail and copy the run ID, correlation ID, immutable version, current stage, and Flink Job ID.
2. Treat PostgreSQL run stages/events, Flink REST state, Pulsar ledger, and Projection ledger as runtime truth. SkyWalking is diagnostic context only.
3. For `COMPILING` or `STARTING`, inspect the safe run diagnostic and JobManager/TaskManager logs. Never print program arguments, Authorization headers, exchanged credentials, or source records.
4. For `PROJECTING`, compare `written_count` with the correlation-scoped `projection_ledger`. A completed Flink job is not a completed pipeline run until the Projection acknowledgement is terminal.
5. Retry a failed or degraded batch run through the API/UI. Retry creates a new run ID, preserves `retry_of` and the original immutable version, and relies on stable event IDs and object versions for idempotency.

## Preview recovery

Preview is an asynchronous, bounded Flink job with a row limit, a 1 MiB result ceiling, masking, expiry, and explicit cancellation. A failed preview does not mutate a draft or publish events. Correct the source/DAG and submit a new preview; do not reuse an expired preview credential grant.

## Streaming recovery

- Stop normally with stop-with-savepoint and wait for the returned savepoint location before changing offsets or subscriptions.
- An offset reset is Admin-only, requires a stopped stream and acknowledged duplicate/loss risk, and records an audit event.
- Resume from a savepoint only when the selected immutable version and state schema are compatible.
- Replay a Projection DLQ batch instead of forcing Flink to reread the whole source when the published events are already durable.

## Credential and Flink checks

The workload token file is mounted read-only into Ontology Core and Flink containers. Exchange grants are scoped to one run/connection, expire, and are revoked after terminal state. Run `make e2e-pipelines` after changing grant SQL, run-state mappings, Flink Java options, or internal security rules.

Flink 1.20 on Java 17 requires the complete `env.java.opts.all` module-open list in `docker/config/flink/flink-conf.yaml`. If JobManager reports `ExecutionContextEnvironment` initialization errors, compare the mounted configuration with the image default before rebuilding. Do not remove unrelated Docker volumes. If a local Pulsar BookKeeper volume is demonstrably corrupt, back up or explicitly target only `ontology-platform_pulsar-data`, recreate it, and rerun the idempotent bootstrap.
