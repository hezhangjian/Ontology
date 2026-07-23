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

- Keep schema names consistent across paths, components, examples, and related documentation.
- Use logical ordering when fields or OpenAPI keys have a natural reading order.
- Use alphabetical ordering when items have no meaningful logical order.
