# P08 object explorer evidence

The phase gate is `make e2e-explorer` (`E:explorer-page`). It verifies:

- bounded Object Set AST validation and a signed cursor bound to caller, normalized query, sort state, and expiry;
- storage-authorized OpenSearch search, typed prefix filtering, facets, stable pagination, and global search;
- HugeGraph-backed detail and relation traversal, including continued detail access during OpenSearch degradation;
- server-side sensitive-field redaction for search and object detail;
- Table/Card/Quick Analysis/Relation Graph/Compare result models and object capabilities, activity, provenance, and comparison endpoints;
- dynamic saved explorations, reference-only static lists, and revocable signed selections;
- private MinIO export with owner checks, SHA-256 evidence, and expiry;
- declarative Action preview and selection gates creating an auditable job without exposing direct CRUD;
- Viewer/Builder/Admin authorization boundaries and caller-attributed audit events.

Frontend evidence is recorded in `timeline.md` after manual clicks through the APISIX origin. The browser pass also caught missing `/api` prefixes in explorer requests; the production portal image was rebuilt and the affected search, detail, and export flows were clicked again with zero console warnings or errors. Action preview remained covered by the role-aware E2E gate.
