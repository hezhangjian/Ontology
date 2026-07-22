# ADR 0006: Publish one shared ontology through immutable revisions

- Status: Accepted
- Date: 2026-07-20

## Context

Object, relation, Interface, Action, Function, mapping, and indexing changes must remain reviewable together. PostgreSQL, HugeGraph, and OpenSearch cannot participate in one distributed transaction, while Projection Worker must interpret every event with the exact contract that existed when the event was produced.

## Decision

The platform maintains one shared ontology with a globally increasing `ontology_revision`. PostgreSQL stores stable resource identities, normalized typed definitions, immutable resource versions, proposals, reviews, deployment steps, health issues, and revision snapshots. It does not store business object bodies.

Creating or editing a resource only creates a draft. A multi-resource Proposal freezes exact draft versions against an active baseline revision, runs compatibility and impact validation, and requires review. Publication is a persisted Saga: lock the Proposal, build the immutable revision contract, verify the additive HugeGraph schema, verify the versioned OpenSearch alias, atomically activate the new revision, then publish audit evidence. Until activation, the previous revision remains the only active contract. Failed physical resources remain orphan candidates for explicit inspection, and retry creates another durable deployment attempt.

Stable physical keys are generated from immutable resource and property identities. Display names never control storage names. API names become immutable after first publication. Projection Worker continues to select `object_types`, `object_properties`, and `relation_types` by exact revision, while the richer normalized model owns authoring, review, history, and deployment state.

Interface is a typed query contract without instances. Action is a declarative mutation definition and cannot execute arbitrary code. Function is a typed, caller-scoped, read-only DSL and cannot expose SQL, Gremlin, native OpenSearch DSL, scripts, or webhooks.

## Consequences

There is no global Git-like branching, direct create-and-deploy path, physical table modeling, or in-place mutation of published versions. Rollback creates a new Proposal from compatible historical metadata and does not claim to restore object values. HugeGraph remains authoritative for object and relation facts; OpenSearch remains a rebuildable search projection.
