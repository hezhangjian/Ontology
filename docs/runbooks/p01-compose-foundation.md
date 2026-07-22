# P01 authenticated Compose foundation

## Purpose

This runbook starts and validates the single-host platform foundation. Keycloak authentication is enabled by default; `dev-insecure` is never implied by these commands.

## Prerequisites

- Docker Engine with Compose v2 on Linux amd64/arm64 or Docker Desktop for local verification.
- Java 21, Node.js 22, and pnpm for source gates.
- At least 8 GiB of Docker memory for the complete observability profile; the checked-in single-host JVM limits are part of this baseline.
- A private secret directory containing every filename present under `deploy/secrets/examples/`.

The committed example secret values are only for isolated local verification. Before a shared deployment, copy their filenames into a protected directory, replace every value, set `SECRET_DIR` in `deploy/env/.env`, and restrict file permissions.

## Build and start

```bash
make compose-build
make compose-up
```

`make compose-up` enables `apps`, `auth`, `compute`, `core`, `gateway`, `maintenance`, and `observability`, then waits for declared health checks. APISIX is the product entry at `http://localhost:9080`; Keycloak binds only to loopback at `http://localhost:8083` for the browser OIDC redirect.

For the standard authenticated platform without the observability plane:

```bash
deploy/scripts/compose.sh \
  --profile apps \
  --profile auth \
  --profile compute \
  --profile core \
  --profile gateway \
  up -d --wait
```

## Validate

Run the source and configuration gates, followed by the P01 runtime gate:

```bash
make verify-fast
make e2e-platform-foundation
```

The runtime gate verifies:

- architecture-compatible digest-locked external images;
- bootstrap-created private MinIO buckets and explicit Pulsar topics;
- Flink connector and JDBC driver hashes inside the running image;
- healthy application, authentication, compute, gateway, observability, and storage services;
- OIDC discovery, product routing, and a `401` response from a protected API without a bearer token.

Complete the remaining PKCE check manually in the Codex in-app browser: open `http://localhost:9080/data/connections`, choose Keycloak login, sign in with a local demo identity, and verify the authenticated shell and logout flow.

## Inspect and recover

```bash
deploy/scripts/compose.sh --profile '*' ps
deploy/scripts/compose.sh --profile '*' logs --tail=200 SERVICE
make compose-down
```

Bootstrap containers are intentionally one-shot and must finish with exit code 0. Their scripts are idempotent, so restarting the dependent profile is safe. `make compose-down` preserves named volumes; do not add `--volumes` unless destruction of all local platform state is explicitly intended.

PostgreSQL 17 uses the `postgres17-data` volume. An older `postgres-data` volume is deliberately left untouched because an in-place major-version data-directory upgrade is unsafe; migrate it with `pg_dump`/`pg_restore` if legacy control-plane data must be retained.

## Security boundaries

- APISIX is the only non-loopback product port in P01.
- Component stores and SkyWalking remain on the private Compose network.
- OpenSearch security is disabled only inside that private network for this single-host foundation; P16 must validate hardening and telemetry isolation before production release.
- The selected public MinIO image predates later community security maintenance. Its API and console must remain private, and every upgrade must repeat Flink S3 plus backup/restore tests.
