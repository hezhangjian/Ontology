# Ontology

Ontology is an open Data and AI platform.

It provides trusted business context for applications, models, and AI agents—connecting data, decisions, and operations.

## Quick start

### Simple ontology development

Use pnpm to start the portal and backend with hot reload:

```bash
pnpm install
pnpm dev:all
```

The portal is available at `http://localhost:5173`, and the backend is available at `http://localhost:4242`.
The default pnpm backend uses an in-memory H2 database; data is reset when the backend restarts.

### Full local stack

Use Docker Compose when you need all local infrastructure components. This mode provides PostgreSQL and the other local infrastructure services.

```bash
docker compose -f docker/docker-compose.yml up -d
```

Run the portal and backend against the PostgreSQL instance from the full stack:

```bash
pnpm dev:all:postgres
```

Stop the full local stack:

```bash
docker compose -f docker/docker-compose.yml down
```

## Documentation

See `docs/README.md` for project documentation.

## API Contracts

See `openapi/README.md` for REST API contracts and authoring guidance.
