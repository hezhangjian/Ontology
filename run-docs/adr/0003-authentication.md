# ADR 0003: Enable Keycloak OIDC by default

- Status: Accepted
- Date: 2026-07-20

## Context

Authentication must be consistent at the gateway, browser, and Java resource-server boundaries.

## Decision

Use Keycloak as the default identity provider. APISIX validates OIDC at the gateway, the portal uses Authorization Code with PKCE, and Java services validate bearer tokens as resource servers. Explicit `dev-insecure` behavior is limited to unit tests and local debugging.

## Consequences

Standard demos and production Compose include the `auth` profile. Browser-provided roles or identities are never trusted without token validation, and navigation visibility is not a substitute for API authorization.
