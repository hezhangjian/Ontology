#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)

exec docker compose \
  --env-file "${project_root}/docker/versions.env" \
  --env-file "${project_root}/docker/env/.env.example" \
  -f "${project_root}/docker/docker-compose.yml" \
  "$@"
