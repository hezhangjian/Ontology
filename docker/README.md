# Docker workspace

`docker/docker-compose.yml` is the single Compose entry point. It includes ordered fragments from `compose/`; `versions.env` locks external images and connector artifacts.

Use the wrapper so every command receives the same version and environment files:

```bash
docker/scripts/compose.sh --profile core --profile compute --profile gateway --profile apps --profile auth up -d --wait
```

The default `make compose-up` command additionally starts the `maintenance` and `observability` profiles. Available profiles are:

- `apps` - BFF, Agent Runtime, and frontend
- `auth` - Keycloak and its Postgres dependency
- `compute` - Flink JobManager and TaskManager
- `core` - platform stores, event bus, Ontology Core, and Projection Worker
- `gateway` - APISIX product entry point
- `maintenance` - restricted Maintenance Runner
- `observability` - SkyWalking OAP/UI and OpenTelemetry Collector

The workspace contains:

- `compose/` - ordered Compose fragments
- `config/` - component configuration
- `images/` - project-owned image definitions
- `scripts/` - idempotent bootstrap and verification scripts

Runtime secrets must not be committed. The files under `secrets/examples/` are conspicuous local-only placeholders. Set `SECRET_DIR` to a protected directory containing files with the same names before any shared or production deployment.

See the [P01 Compose runbook](../docs/runbooks/p01-compose-foundation.md) for startup, validation, and recovery procedures.
