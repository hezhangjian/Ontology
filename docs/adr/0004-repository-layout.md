# ADR 0004: Use a bounded monorepo layout

- Status: Accepted
- Date: 2026-07-20

## Context

The repository needs stable ownership boundaries before vertical slices add schema, API, backend, frontend, and tests together.

## Decision

Use `backend/`, `docker/`, `docs/`, `examples/`, and `portal/` as the top-level implementation directories. The root Maven project only aggregates Java modules. Public HTTP contracts live only in `docs/openapi/openapi.yaml`.

## Consequences

Business Java source does not return to a root `src/` directory. Service implementation modules do not depend on each other; stable shared contracts and test utilities use their dedicated modules.
