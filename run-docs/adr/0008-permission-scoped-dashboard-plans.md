# ADR 0008: Compile immutable dashboard plans and execute them per caller

- Status: Accepted
- Date: 2026-07-21

## Context

Published dashboards combine reusable object-set sources, filters, aggregates, object tables, and static presentation components. A definition interpreted by the browser would expose query construction, make historical behavior non-reproducible, and risk sharing cached results across callers with different row or field permissions. Dashboard publication must also leave the currently served version intact when validation or sample execution fails.

## Decision

Dashboard drafts use ETags and a renewable 15-minute single-editor lease. Publication validates the normalized definition, freezes its ontology revision and stable resource identifiers, compiles an immutable `DashboardQueryPlan`, performs a bounded sample execution with the publisher's permissions, and only then atomically changes the current version pointer. Historical restoration creates a new draft and can never reactivate an old version in place.

Runtime execution accepts only a published plan identifier, declared page/widget identifiers, normalized filter values, and a refresh identifier. The server reuses the P08 Object Set runtime, rechecks dashboard, source, row, and property access for the current caller, then applies filtering, aggregation, and the minimum-group threshold. Drilldown uses a short-lived signed caller-bound token; suppressed groups cannot be drilled into.

Result caches are caller scoped. Their key includes the immutable version and plan hashes, normalized filters, caller security-context hash, ontology and policy revisions, and source watermark. PostgreSQL stores dashboard identity, normalized draft/version structure, permissions, plan metadata, execution metadata, locks, favorites, and audit records only. HugeGraph remains the object truth and OpenSearch remains the rebuildable search projection.

## Consequences

The browser cannot submit raw SQL, Gremlin, PPL, native OpenSearch DSL, arbitrary JavaScript, or Action requests through a dashboard. Sharing a dashboard definition never grants access to its underlying objects or fields. Failed validation or publication cannot replace the live version. Cached results, counts, empty states, and drilldown tokens cannot cross security contexts, and small groups remain suppressed at every runtime boundary.
