# P07 ontology modeling evidence

The phase gate is `make e2e-modeling` (`E:modeling-page`). It verifies:

- normalized stable resources and typed immutable versions for objects, properties, links, Interfaces, Actions, and Functions;
- draft-only creation, exactly one scalar primary key, JSON and array restrictions, sensitive-search defaults, and stable physical keys;
- Viewer read-only access, Builder authoring, and Admin review/publication separation;
- Action conflict-write rejection and short-lived preview planning;
- Function read-only DSL validation, caller permission preservation, and exact version binding;
- multi-resource Proposal validation, review, impact, audit, and publication;
- real HugeGraph schema and OpenSearch alias checks inside a persisted deployment Saga;
- an OpenSearch outage leaving the previous revision active, followed by successful retry and retained failed-attempt evidence;
- exact-revision Projection Worker consumption and exclusion of a sensitive property from OpenSearch.

Frontend evidence is recorded in `timeline.md` after manual clicks through the APISIX origin with console warning/error capture.
