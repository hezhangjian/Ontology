# P09 analytics dashboard operations

## Start and verify

From the repository root:

```sh
make compose-up
docker/scripts/healthcheck.sh
make e2e-dashboards
```

The phase gate creates and publishes versioned dashboards, executes their immutable query plans, and verifies draft leases, ETags, permissions, cache isolation, filters, suppression, drilldown, lifecycle operations, and audit. It must end with `E:dashboards-page passed`.

Use `http://localhost:9080` for browser verification. Dashboard requests must remain below `/api/ontology/v1/...` through APISIX. The portal must never connect directly to PostgreSQL, HugeGraph, OpenSearch, or a model provider.

## Diagnose draft and publication failures

1. Inspect the active draft ETag and the `dashboard_edit_locks` lease owner and expiry. A lost lease or stale ETag must be resolved by reloading or intentionally creating a new draft; never overwrite another editor's changes.
2. Run validation and inspect issue paths for pages, widgets, data sources, stable property identifiers, layout limits, and permissions.
3. Check that referenced object types and properties exist in the frozen ontology revision and that the publisher can execute every source.
4. Inspect publication audit and query-plan metadata. A failed candidate must not replace `current_version_id`; correct the draft and publish a new immutable version.
5. For a historical restore, use “create draft from version”, validate against current dependencies, and publish a new version. Do not move the live pointer backward.

## Diagnose runtime failures

- Verify dashboard access first, then the caller's source, row, and property permissions. Dashboard grants do not imply data grants.
- Inspect the plan hash, version, normalized filter hash, caller security-context hash, ontology revision, policy revision, and watermark used by the cache key.
- Treat OpenSearch as a rebuildable projection. Restore or rebuild it rather than placing object bodies or result caches in PostgreSQL.
- Keep the default minimum-group threshold at 5. Suppressed buckets must not become available through tooltip, drilldown, export, logs, or AIP context.
- Regenerate expired drilldown tokens. Tokens are signed, short lived, purpose scoped, and caller bound.
- Use component correlation IDs and query-run metadata to isolate a partial failure; one component failure must not invalidate successful sibling results.

## Build a self-service Dataset chart

Dataset charts do not require an administrator-maintained semantic model. In the widget inspector:

1. Select a raw Dataset field for the horizontal axis. For date-like values, select day, week, month, quarter, or year; bucketing is executed by the server.
2. Optionally select a series field. Use month on the horizontal axis and leader as the series for trends, or leader on the horizontal axis and month as the series for grouped comparisons.
3. Add up to four measures. Each measure independently selects count, sum, average, minimum, maximum, distinct count, or sum divided by distinct count. The latter supports per-person metrics by using the person identifier as the denominator field.
4. Add widget-local field filters when the chart should be fixed to a department, team, or other raw value.
5. Publish the dashboard. Query execution continues to use the immutable server-side plan; the browser never submits SQL or native infrastructure queries.

Existing dashboards that use `dimensionPropertyIds`, `aggregation`, and `measurePropertyId` remain compatible. New Dataset chart definitions use explicit field roles and a `measures` list.

## Safe lifecycle operations

Only an Owner can publish, manage permissions, archive, or restore. A published dashboard is archived rather than deleted. Permanent deletion is limited to an empty dashboard that has never been published and has no dependencies. Copying duplicates the definition and dependency references but not permissions, favorites, usage, or cached results.

## Data ownership

PostgreSQL contains normalized control-plane state and bounded execution metadata only. HugeGraph owns object and relation facts, OpenSearch owns rebuildable search projections, and any future authorized export artifact belongs in private MinIO storage.
