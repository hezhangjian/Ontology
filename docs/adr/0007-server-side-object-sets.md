# ADR 0007: Evaluate object sets and authorization on the server

- Status: Accepted
- Date: 2026-07-20

## Context

Object exploration combines text search, typed filters, facets, stable pagination, graph traversal, saved resources, selection, export, and declarative Actions. A browser-side object ID list would leak authorization boundaries, become stale between pages, and allow a cursor, selection, or export request to be replayed by another user. No single storage system is authoritative for every part of this workflow.

## Decision

The browser submits a bounded Object Set AST to the platform API. The API validates depth, condition, relation-hop, field, page-size, and sort limits before execution. OpenSearch evaluates storage-level visibility tokens together with search, typed filters, facets, sort, and cursor pagination. Cursor tokens are HMAC signed and bind the caller, normalized query identity, sort state, and expiry.

HugeGraph remains authoritative for object bodies and relationships. Detail and relation endpoints read it directly and apply field-level sensitive-data policy on the server. PostgreSQL stores only control-plane metadata: saved explorations, reference-only lists, short-lived token ledgers, export and Action job metadata, audit events, and immutable ontology contracts. Private export artifacts are written to MinIO with a hash and expiry; every metadata or download access rechecks the owner.

Selection tokens are HMAC signed, short lived, caller bound, query bound, and backed by a revocable ledger. Action requests require both the selection token and a fresh declarative Action preview token; P08 creates an auditable gated job but does not introduce arbitrary CRUD or script execution. Search degradation returns an explicit service-unavailable response while HugeGraph-backed detail remains available.

## Consequences

Clients cannot send native OpenSearch DSL, Gremlin, SQL, arbitrary object IDs for bulk operations, or reusable bearer-like cursors. Search projections can be rebuilt without changing object truth. Saved static lists store references rather than object bodies. Exports and bulk jobs are asynchronous control-plane resources, and all sensitive redaction and authorization decisions remain server side.
