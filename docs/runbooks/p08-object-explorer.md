# P08 object explorer operations

## Start and verify

From the repository root:

```sh
make compose-up
deploy/scripts/healthcheck.sh
make e2e-explorer
```

The phase gate seeds a published Employee contract and deterministic object facts, projects them to HugeGraph and OpenSearch, then verifies authorized search, facets, cursor continuity, graph detail, saved resources, signed selection, private export, Action preview gating, RBAC, and audit. It must end with `E:explorer-page passed`.

Use `http://localhost:9080` for browser verification. Browser calls must stay under `/api/ontology/v1/...` through APISIX; do not point the portal at infrastructure endpoints.

## Diagnose search failures

1. Call the explorer capabilities endpoint and inspect the reported OpenSearch status and projection time.
2. Verify that the active ontology revision exposes only searchable, non-sensitive properties in the projection contract.
3. Check the `platform-ontology-objects` alias and rebuildable index. Never copy business object bodies into PostgreSQL to compensate for a failed index.
4. Treat a search `503` as projection degradation. HugeGraph-backed object detail and relationship traversal should continue to work.
5. After recovery, rerun the same Object Set without reusing an expired cursor. A cursor is valid only for the same caller and normalized query.

## Token and authorization recovery

- Reject a cursor, selection token, or Action preview token when its signature, owner, query fingerprint, purpose, or expiry differs.
- Revoke a compromised selection in the PostgreSQL ledger. Do not extend an existing token in place.
- Keep visibility predicates inside the OpenSearch query so counts and facets describe only authorized objects.
- Apply sensitive-field masking again when reading HugeGraph, regardless of what the projection contains.

## Export recovery

1. Inspect the export control-plane row for owner, selection reference, status, format, row count, SHA-256, object key, and expiry.
2. For a failed job, preserve its safe error and audit trail. Create a new export after correcting the dependency; do not mutate a failed artifact into success.
3. Verify the private MinIO object hash before serving it and recheck the caller on every status or download request.
4. Expired exports may be removed from MinIO by retention processing while their minimal audit metadata remains.

## Data ownership

OpenSearch owns rebuildable search projections, HugeGraph owns object and relation facts, MinIO owns private export artifacts, and PostgreSQL owns explorer control state only. Saved lists contain typed references and never duplicate object bodies.
