# P03 data connections architecture

P03 delivers the `/data/connections` vertical slice across the portal, APISIX, Ontology Core, PostgreSQL, and external source adapters. `docs/openapi/openapi.yaml` remains the HTTP contract source of truth.

## Ownership and persistence

- `control.data_sources` owns non-sensitive connection metadata, validated target scope, lifecycle status, optimistic-lock version, and a `secret_ref` only.
- `control.connection_secrets` stores either AES-256-GCM ciphertext plus nonce/key version, an existing credential reference, or allowlisted `file://` Docker Secret references. APIs, audit events, diagnostics, and logs never return credential bodies.
- Discovery runs, assets, fields, tests, pipelines, runs, and audit events use separate control-plane tables. A data connection never stores business-object bodies in PostgreSQL.
- Permanent deletion requires `DISABLED`, zero pipeline references, and zero active runs. Audit history retains a safe connection snapshot.

## Adapter boundary

`ConnectionProbe` implements real read-only adapters for five source types:

- external Pulsar Admin metadata;
- Kafka Admin metadata;
- MinIO/S3 signed bucket/object listing plus bounded CSV schema inference and preview;
- MySQL JDBC metadata and bounded preview;
- PostgreSQL JDBC metadata and bounded preview.

Connection policy validates type-specific fields, target protocols and ports, timeouts, the platform-Pulsar separation, and private-network allowlists before an adapter opens a socket. Preview is capped at 50 or 100 rows and 1 MiB. Kafka/Pulsar preview paths never commit offsets or create persistent subscriptions.

## Credential and test transaction

- Managed values are encrypted with AES-256-GCM using the versioned `connection_master_key` Docker Secret.
- Test tokens are HMAC-authenticated, expire after 15 minutes, and bind the stable actor, source type, recursively canonicalized config, credential fingerprint, and successful test status.
- Browser lightweight tokens use `sub`, then `preferred_username`, then `client_id` as the stable actor chain.
- Create performs a final probe before committing. Target edits probe with the currently referenced credential in the same transaction and preserve the old config on failure.
- Credential rotation probes the candidate first, atomically swaps `secret_ref`, adjusts reference counts, and preserves the old credential on failure.

## Portal routes and lifecycle

- `/data/connections` keeps search/type/status/owner/page filters in the URL.
- `/data/connections/new` is a four-step type, config, test/discovery, confirmation wizard.
- `/data/connections/{id}` exposes Overview, Assets, Pipelines, Runs, and Settings tabs in the URL.
- `/data/connections/{id}/assets/{assetId}` restores the asset drawer; closing returns to `?tab=assets`.
- `/data/connections/{id}/edit` directly saves metadata or performs a real test-and-save when target scope changes.

Builder/Admin can create, test, edit, rotate, stop, and restore. Only Admin can permanently delete or reference Docker Secrets. Viewer receives 403 and has no data-connections navigation. Restored connections become `UNTESTED`; discovery, preview, and pipeline creation require `HEALTHY` or `HEALTHY_RESTRICTED`.
