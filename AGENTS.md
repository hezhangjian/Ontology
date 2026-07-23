# AGENTS.md

## Project Overview

This repository is organized as:

- `src/` - Java application source code.
- `portal/` - Frontend application workspace.
- `openapi/` - HTTP API contracts and related OpenAPI authoring documentation.
- `docker/` - Local infrastructure and service configuration.
- `docs/` - Project documentation.

When adding or updating REST API contracts, use `openapi/`.

## Package Manager

- Use `pnpm` for all JavaScript/TypeScript package operations (install, add, remove, run scripts).
- Never use `npm` or `yarn` commands.

## Code Style

- All code comments must be written in English.
- Commit messages must follow [Conventional Commits](https://www.conventionalcommits.org/) specification:
    - Format: `<type>(<scope>): <description>`
    - Common types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `build`
    - Example: `feat(core): add zip utilities for archive operations`
- All commits must be signed-off using the `-s` flag (`git commit -s`).
- When listing parallel items with no specific logical relationship, sort them alphabetically.

## Documentation

- Keep HTTP API contracts and related OpenAPI authoring documentation under `openapi/`.
- Treat `openapi/ontology.yaml` as the source of truth for REST API definitions.
