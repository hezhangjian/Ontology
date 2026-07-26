# OpenAPI

`ontology.yaml` is the source of truth for REST API contracts.

## Naming

### Resources

- Use singular resource names for OpenAPI tags, such as `Ontology`, `Term`, and `Category`.
- Use plural nouns for collection paths, such as `/ontologies` and `/terms`.
- Use `id` for a resource's own identifier field.
- Use `xxxId` for path parameters and foreign-key/reference fields.

### CRUD

- For simple CRUD APIs, name create request schemas as `CreateXxxReq`.
- For simple CRUD APIs, name resource schemas as `Xxx`.
- For simple CRUD APIs, return the resource schema `Xxx` from create and update operations.

### Ordering

- Prefer reader-friendly logical ordering over alphabetical ordering.
- Order paths by resource hierarchy: place parent resources before their child resources.
- Within one resource, order operations by the normal CRUD flow: list, create, get, update, delete.
- Order schemas near the resources that use them, and keep related request and response schemas together.
- Within schemas, order fields by their role:
  - own identifier first, such as `id`;
  - primary display fields next, such as `name`;
  - parent or reference identifiers next, such as `ontologyId`;
  - descriptive fields next, such as `description`;
  - behavioral fields next;
  - metadata fields last, such as `createdAt` and `updatedAt`.
- Keep schema names consistent across paths, components, examples, and related documentation.
- Use alphabetical ordering only when items have no meaningful hierarchy, workflow, or dependency relationship.
