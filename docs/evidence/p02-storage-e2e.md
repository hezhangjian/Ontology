# P02 storage verification evidence

The executable source of evidence is `docker/scripts/e2e-storage.sh`. It uses real project containers and no mocked storage service.

| Concern | Assertion |
|---|---|
| Atomic mutation | Three object/relation edits produce three projected ledger entries with one attempt and one operation ledger entry. |
| Data ownership | PostgreSQL has no `object_bodies`, `objects`, or `ontology_objects` table. |
| DLQ | An unknown property increments both the permanent failure ledger and the partitioned DLQ message counter; a storage outage reaches DLQ after three retryable attempts. |
| Idempotency | Repeating an event or mutation key does not increment projection attempts. |
| Index rebuild | The recorded successful target equals the active OpenSearch alias target. |
| Ordering | A distinct late version becomes `STALE`; version 2 remains in HugeGraph and OpenSearch. |
| Recovery | A real OpenSearch outage produces `DEGRADED`; redelivery reaches `PROJECTED` with at least two attempts after recovery. |
| Sensitive data | The full email exists in HugeGraph while OpenSearch omits it. |
| Tombstone | The deleted object is absent from both HugeGraph and OpenSearch. |

The test uses `pulsar-client --files`; JSON must not be passed with `--messages`, because that CLI option treats commas as message separators.
