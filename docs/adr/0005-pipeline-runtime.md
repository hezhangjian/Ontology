# ADR 0005: Use one versioned Pipeline IR and Flink runtime

- Status: Accepted
- Date: 2026-07-20

## Context

Preview and production execution must not drift, published behavior must remain reproducible, and credentials must not become part of durable pipeline artifacts.

## Decision

The portal persists a typed DAG. Ontology Core validates and normalizes that DAG, then compiles an immutable `ontology.pipeline/v1` IR and a signed Flink job specification. The same controlled Flink job executes bounded previews and published batch or streaming runs. Preview truncates the normalized graph at the selected node and writes only to its bounded result callback. Production jobs publish ontology events only to platform Pulsar; Projection Worker remains responsible for HugeGraph and OpenSearch.

Each run references an exact immutable pipeline version. Runtime credentials are exchanged over an internal workload endpoint using a service token, run ID, signed job identity, connection scope, active-run check, and short TTL. Pipeline IR, job arguments, audit events, logs, and Flink metadata contain resource identifiers but no credential values.

A batch run is complete only after Flink has finished publishing and the Projection batch acknowledgement confirms the expected event ledger. PostgreSQL stores control-plane versions, grants, stages, checkpoints, events, schedules, and acknowledgements; it does not store business object bodies.

## Consequences

There is no alternate Java preview interpreter, user-provided JAR, SeaTunnel path, or direct Flink-to-HugeGraph/OpenSearch output. Rollback activates an older immutable version for future runs and never claims to undo already projected business data. Streaming stop and offset reset are savepoint-aware, and durable run events support reconnectable SSE without resubmitting work.
