# P02 ontology projection foundation

## Ownership and flow

Projection Worker is the sole production writer to the ontology stores:

```text
typed Pulsar event or mutation batch
  -> immutable ontology revision validation
  -> PostgreSQL projection/operation ledger
  -> HugeGraph fact transaction
  -> OpenSearch derived search document
  -> Pulsar acknowledgement
```

PostgreSQL contains revision metadata, property contracts, idempotency facts, failures, operations, and rebuild jobs. It deliberately has no table for object bodies. HugeGraph stores complete object and relation payloads. OpenSearch stores only searchable, non-sensitive properties and can be discarded and rebuilt from HugeGraph.

## Contracts

`platform-contracts` owns snake-case JSON records for:

- index rebuild commands;
- ontology event envelopes and source lineage;
- ontology mutation batches and typed edits.

Schema version 1 requires a globally unique `event_id`, an immutable `ontology_revision`, a stable `correlation_id`, and a positive entity version. The seeded revision demonstrates `Department`, `Employee`, and `member_of`; `Employee.email` is sensitive and non-searchable.

Object and relation events use keyed Pulsar messages. Mutation batches are limited to 100 edits, derive deterministic event IDs from the caller idempotency key, validate every edit before writing, and apply graph changes in one HugeGraph transaction. OpenSearch is applied after the graph commit; a retry continues from the graph ledger checkpoint instead of repeating the graph transaction.

## Consistency states

The event ledger advances through `RECEIVED`, `GRAPH_APPLIED`, and `PROJECTED`. A search outage records `DEGRADED` and uses Pulsar negative acknowledgement. A newer entity version makes a late event `STALE`. Permanent contract failures and exhausted retries are published to `persistent://platform/dlq/projection-events`; the ledger records `DLQ` when the event contract was valid enough to identify its event ID.

`platform-ontology-objects` and `platform-ontology-relations` are project-owned aliases. Rebuild commands create timestamped `platform-ontology-objects-rebuild-*` indexes and atomically switch the object alias after every document has been read from HugeGraph and indexed. This namespace does not modify unrelated or legacy OpenSearch indexes.

## Migration policy

`V1__projection_control_plane.sql` is the initial control-plane migration. After a shared environment has applied it, never edit V1; use a new forward-only `V2__*.sql` migration and document any repair or backfill in the phase runbook. Object-body tables or PostgreSQL JSON mirrors are prohibited in forward migrations.
