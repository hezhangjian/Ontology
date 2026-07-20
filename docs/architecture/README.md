# Architecture

The frozen architecture, component ownership, data flows, ports, and deployment profiles are specified in [plan.md](../../plan.md), sections 3–10.

Implementation evidence must preserve these boundaries:

- Agent Runtime accesses platform data only through typed Ontology APIs.
- Flink writes only to the platform Pulsar event bus.
- HugeGraph is the object and relation fact store.
- OpenSearch is a rebuildable search projection.
- PostgreSQL contains no business-object bodies.
- Projection Worker is the only production projection path to HugeGraph and OpenSearch.

Implemented architecture notes:

- [P02 ontology projection foundation](p02-projection-foundation.md)
