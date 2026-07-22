# P09 analytics dashboard evidence

The phase gate is `make e2e-dashboards` (`E:dashboards-page`). It verifies:

- dashboard creation, normalized drafts, 15-minute edit leases, ETag conflicts, validation, and immutable v1/v2 publication;
- failed candidate validation preserving the current published version;
- Viewer, Editor, and Owner authorization boundaries plus caller-attributed audit events;
- immutable Dashboard Query Plans backed by the P08 Object Set runtime;
- caller-scoped result caches with permission-context separation;
- explicit property-bound filters and server-side filter options;
- metric, grouped aggregate, object table, static component, and batched execution results;
- minimum-group threshold 5, suppressed bucket behavior, and signed caller-bound drilldown tokens;
- favorites, copy semantics, empty-draft deletion, archive, and restore;
- permission, health, usage, diff, and create-draft-from-version control-plane endpoints.

Manual frontend evidence is recorded in `timeline.md`. The in-app browser pass created a two-page dashboard, configured an Employee Object Set, added metric/bar/object-table/Markdown widgets, bound the department stable property ID, observed autosave, validated, and published v1. Runtime verification observed 336 authorized objects, grouped results with small buckets suppressed, an object table without email, cache status, favorites, page switching, fullscreen mode, version history, and current-permission historical-definition preview. The pass found that the editor stacking context hid Ant Design overlays; lowering the full-screen shell below the standard overlay layer fixed the modal and select, and the rebuilt production image passed with zero console warnings or errors.
