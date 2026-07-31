# Ontology

Ontology is an open Data and AI platform.

It provides trusted business context for applications, models, and AI agents—connecting data, decisions, and operations.

## Quick start

### Serve the application

Install the dependencies, then build and serve the portal and backend with Maven and JDK 25:

```bash
pnpm install
pnpm serve
```

`pnpm serve` builds the portal, packages it into `target/ontology.jar`, and runs the Spring Boot server with the default
`local` profile on `0.0.0.0:4242`.
Open `http://localhost:4242` locally or `http://<server-ip>:4242` from another machine. The portal and `/v1` API share the same origin.
Press `Ctrl+C` to stop the server.

To package manually with the bundled portal:

```bash
pnpm build
mvn -Pportal clean package
```

To run the existing build without rebuilding:

```bash
java -jar target/ontology.jar --server.address=0.0.0.0
```

The `local` profile uses in-memory H2 and creates the JPA tables on startup; data is discarded when the process exits.
It supports basic ontology management without external infrastructure. PostgreSQL migrations, message consumers,
scheduled background work, and projection storage initialization are disabled in this profile.
Features that depend on the other platform tables or external services still require further local adaptation.

To use the full infrastructure, configure the required values in `docker/.env` following `docker/.env.example`,
including `POSTGRES_PASSWORD`, `POSTGRES_URL`, and `POSTGRES_USER`, then run `SPRING_PROFILES_ACTIVE=postgres pnpm serve`.

The backend dependencies and build plugins are declared directly in `pom.xml`; `portal` only bundles the frontend.
The Flink job keeps its Java 17 build and dependencies in `pom-flink.xml`. Build it with `mvn -f pom-flink.xml package`
or `make flink-package`.

### Simple ontology development

Use pnpm to start the portal and backend with hot reload:

```bash
pnpm install
pnpm dev:all
```

The portal is available at `http://localhost:5173`, and the backend uses the same default `local` profile at `http://localhost:4242`.
Vite proxies `/v1` requests to the backend during development. Set `VITE_API_BASE_URL` only when using a separate API origin.

### Full local stack

Use Docker Compose when you need all local infrastructure components:

```bash
docker compose -f docker/docker-compose.yml up -d
```

Stop the full local stack:

```bash
docker compose -f docker/docker-compose.yml down
```

## Documentation

See `docs/README.md` for project documentation.

## API Contracts

See `openapi/README.md` for REST API contracts and authoring guidance.
