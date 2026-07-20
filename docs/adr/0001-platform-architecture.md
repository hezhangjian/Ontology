# ADR 0001: Simplified event-driven platform architecture

- Status: Accepted
- Date: 2026-07-20

## Context

The platform needs one reproducible deployment for a single Linux server while supporting batch and streaming ingestion, ontology projection, applications, and AIP workloads.

## Decision

Use Docker Compose and the following data path: Flink writes versioned ontology events to the platform Pulsar bus; a dedicated Java Projection Worker validates and projects events into HugeGraph and OpenSearch. Ontology Core owns control-plane rules and exposes typed APIs through APISIX. SkyWalking and OpenTelemetry provide the observability plane.

## Consequences

Flink never writes the production stores directly. Kubernetes, Nessie, Trino, SeaTunnel, and Iceberg are outside the current implementation boundary. Projection retry, idempotency, and DLQ behavior become platform responsibilities.
