# P07 ontology modeling operations

## Start and verify

From the repository root:

```sh
make compose-up
deploy/scripts/healthcheck.sh
make e2e-modeling
```

The E2E gate creates object, relation, Interface, Action, and Function drafts; validates role separation and type rules; publishes a multi-resource Proposal; stops OpenSearch to force a real Saga failure; verifies that the previous revision remains active; restores OpenSearch; retries publication; and projects an event with the exact new revision. It must end with `E:modeling-page passed`.

Use `http://localhost:9080` for browser verification. Click the global “本体管理” entry, then use the page-local navigation. Direct frontend container ports do not proxy `/api/ontology/*`.

## Diagnose a failed publication

1. Open the Proposal and copy the deployment ID, target revision, failed step, safe error, and attempt.
2. Confirm the currently active revision in `control.ontology_revisions`. A failed target must remain `DRAFT`; it must never replace the active revision.
3. For `DEPLOY_HUGEGRAPH`, verify the generic `ontology_object` and `ontology_relation` schemas through the private HugeGraph API. Do not expose that management API to the browser.
4. For `MIGRATE_OPENSEARCH`, verify the `platform-ontology-objects` alias and its target. Do not switch an alias until validation succeeds.
5. Repair the dependency, then use the Admin retry action. Do not edit deployment rows, mark a failed target active, or delete the old active revision.
6. Review `ontology_health_issues` for the aggregated deployment key. Successful retry resolves the issue but preserves the failed deployment and orphan candidate revision for audit.

## Proposal conflict recovery

A Proposal records its baseline revision. If another publication activates a newer revision, revalidate before publication. For a changed resource, explicitly choose whether to preserve the draft, accept the active version, or author a new resolved draft. Do not simulate a general Git rebase or silently overwrite the newer version.

## Storage and contract checks

- Stable resource and property IDs own physical keys. Display names may change safely; published API names do not.
- Every published object type has exactly one required `STRING`, `INTEGER`, or `LONG` primary key.
- Sensitive properties are written to HugeGraph payloads but are not included in the OpenSearch searchable contract by default.
- Events carry an exact `ontology_revision` and schema version. Never reinterpret historical events using the latest draft.
- PostgreSQL contains modeling and operational control state only. Object and relation bodies remain in HugeGraph.

## Rollback

Select a historical immutable version and create a new Proposal. Re-run validation, impact analysis, review, and the deployment Saga. Rollback does not mutate historical rows and does not restore business object values; data backfill or restoration is a separate migration operation.
