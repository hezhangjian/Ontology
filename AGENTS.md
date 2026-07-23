# AGENTS.md

## Project Overview

This repository is organized as:

- `backend/` - Java 21 Maven modules for platform services and shared contracts.
- `docker/` - The single-file local development entry point `docker-compose.yml`.
- `docs/` - Architecture, ADR, OpenAPI, evidence, and runbook documentation.
- `examples/` - Reproducible, non-sensitive examples and demo data.
- `portal/` - React and TypeScript frontend workspace.

The root `pom.xml` is an aggregator and dependency-management project only. Do not add business source code under a root `src/` directory.

## Package Managers

- Use the Maven Wrapper (`./mvnw`) for Java operations.
- Use `pnpm` for all JavaScript and TypeScript package operations.
- Never use `npm` or `yarn` commands.
- Use Java 21. The Maven build rejects other feature releases.

## Code Style

- All code comments must be written in English.
- Commit messages must follow [Conventional Commits](https://www.conventionalcommits.org/) specification:
  - Format: `<type>(<scope>): <description>`
  - Common types: `build`, `chore`, `docs`, `feat`, `fix`, `refactor`, `style`, `test`
  - Example: `feat(core): add zip utilities for archive operations`
- All commits must be signed off using the `-s` flag (`git commit -s`).
- When listing parallel items with no specific logical relationship, sort them alphabetically.

## Contracts and Architecture

- Keep all public HTTP API contracts in `docs/openapi/openapi.yaml`.
- Treat `docs/openapi/openapi.yaml` as the single source of truth for REST API definitions.
