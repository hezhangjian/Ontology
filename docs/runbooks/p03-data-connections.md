# P03 data connections operations

## Start and verify

From the repository root:

```sh
make compose-up
deploy/scripts/healthcheck.sh
make e2e-connections
```

The E2E gate creates temporary Keycloak Admin/Builder/Viewer clients, seeds `raw/p03-e2e/orders.csv`, exercises the real MinIO adapter, and deletes its temporary connection. It must end with `E:connections-page passed`.

## Master key

`CONNECTION_MASTER_KEY_FILE` must contain base64 that decodes to exactly 32 bytes. Compose mounts `connection_master_key` read-only at `/run/secrets/connection_master_key`. Production must replace the example value before storing managed credentials.

Every ciphertext records `key_version`. Rotate the master key with a controlled decrypt/re-encrypt migration before advancing `CONNECTION_KEY_VERSION`; do not replace the file in place while old ciphertext still depends on it. Back up the key separately from PostgreSQL and restrict access to the Ontology Core runtime.

## Diagnose a connection

1. Copy the request ID and failed stage from the portal diagnostic drawer.
2. Check the connection policy first: protocol, host allowlist, port, target scope, and timeout.
3. Re-run **Test connection**. The five stages are Network, TLS, Authentication, Metadata, and Discovery.
4. Inspect safe audit/test rows by request ID. Do not log request bodies, Authorization headers, credential ciphertext, decrypted values, or source records.
5. For a bad candidate credential, retry rotation with a corrected value; the current credential remains referenced until the candidate probe succeeds.

## Lifecycle recovery

- Stop blocks new discovery, preview, and task starts but retains assets, runs, lineage, audit, and generated objects. Active streaming runs must be savepoint-stopped from the pipeline page first.
- Restore sets the connection to `UNTESTED`. Run a successful connection test before discovery, preview, or pipeline creation.
- Permanent deletion is Admin-only and requires a stopped connection with no pipeline references or active runs. Shared credentials remain; an unreferenced credential is removed transactionally.
- A failed discovery/schema inference/preview does not replace the current connection or cached assets. Retry the individual operation after fixing the target dependency.

## Secret-hygiene check

Run `make e2e-connections`. It asserts that managed credential plaintext is absent from API responses, audit rows, stored connection config, and recent Ontology Core logs; it also verifies that `connection_secrets` contains encrypted material instead of plaintext.
