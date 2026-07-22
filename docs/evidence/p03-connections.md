# P03 data connections verification evidence

The executable evidence is `deploy/scripts/e2e-connections.sh`. It uses real Keycloak tokens, APISIX routing, Ontology Core, PostgreSQL, Docker Secrets, and MinIO.

| Concern | Assertion |
|---|---|
| Audit | Create, edit, test, preview, stop, restore, rotate, and delete emit safe audit events. |
| Credential binding | HMAC test tokens bind actor, type, canonical config, credential fingerprint, status, and expiry. |
| Credential storage | Managed values are AES-256-GCM ciphertext with nonce/key version; connection config contains no secret material. |
| Discovery | The real MinIO adapter discovers the bucket and seeded CSV asset. |
| Lifecycle | Stopped connections reject protected operations; restore requires a fresh successful test; guarded delete succeeds only after stop. |
| Optimistic locking | A stale `If-Match` update is rejected without overwriting the current version. |
| Preview | CSV preview returns the expected first order while enforcing row and 1 MiB response caps. |
| Roles | Viewer receives 403; Builder cannot delete; Admin can complete guarded deletion. |
| Schema | CSV inference returns `order_id`, `customer`, `total`, and `created_at` with stable asset/schema identifiers. |
| Secret hygiene | Credential plaintext is absent from responses, audits, configs, and recent service logs. |
| Transactionality | A duplicate-name create rolls back the candidate credential; failed rotation leaves the old reference effective. |

Final P03 runs:

- `make verify-fast`: 11 Java tests across the reactor, portal lint/typecheck/build, OpenAPI lint, and Compose config passed; Ontology Core contributed 6 connection/security tests.
- `make e2e-connections`: passed after the final credential-fingerprint and edit/rotation changes.
- Codex in-app browser: completed the four-step wizard, all five detail tabs, asset deep link, four-field Schema, 50-row/1-MiB preview, real target test-and-save, credential rotation, stop/restore/retest, and Admin delete confirmations with zero final console warnings/errors.
