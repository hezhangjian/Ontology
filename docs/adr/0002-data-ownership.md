# ADR 0002: Separate control-plane and business-object storage

- Status: Accepted
- Date: 2026-07-20

## Context

Mixing metadata and business-object bodies in PostgreSQL would create competing sources of truth and undermine projection recovery.

## Decision

PostgreSQL stores only control-plane metadata, configuration, policy, audit, task, and idempotency facts. HugeGraph is the fact store for ontology objects and relations. OpenSearch contains rebuildable search projections. MinIO stores files, checkpoints, exports, and backups.

## Consequences

No `object_records` or equivalent object-body table may be introduced. Object reads are reconciled through Ontology Core, and OpenSearch failure degrades search without replacing HugeGraph as the source of truth.
