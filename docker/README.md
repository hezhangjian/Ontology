# Docker workspace

`docker/docker-compose.yml` is the single Compose entry point. P00 keeps an empty, valid service map so configuration validation is active before infrastructure is introduced.

P01 will add the frozen service versions, profiles, health checks, bootstrap jobs, and the following directories:

- `compose/` - ordered Compose fragments
- `config/` - component configuration
- `images/` - project-owned image definitions
- `scripts/` - idempotent bootstrap and verification scripts

Runtime secrets must not be committed. Copy `env/.env.example` to the ignored `env/.env` only for local development.
