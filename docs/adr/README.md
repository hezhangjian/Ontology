# Architecture decision records

This directory records implementation-level decisions derived from the frozen decisions in `../plan.md`.

| ADR | Decision |
|---|---|
| [0001](0001-platform-architecture.md) | Use the simplified event-driven platform architecture |
| [0002](0002-data-ownership.md) | Separate control-plane and business-object storage |
| [0003](0003-authentication.md) | Enable Keycloak OIDC by default |
| [0004](0004-repository-layout.md) | Use the backend, docker, docs, and portal layout |
| [0005](0005-pipeline-runtime.md) | Use one versioned Pipeline IR and Flink runtime |
| [0006](0006-shared-ontology-revisions.md) | Publish one shared ontology through immutable revisions |
| [0007](0007-server-side-object-sets.md) | Evaluate object sets and authorization on the server |
| [0008](0008-permission-scoped-dashboard-plans.md) | Compile immutable dashboard plans and execute them per caller |
