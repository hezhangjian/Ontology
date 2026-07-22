#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)

exec docker compose \
  --env-file "${project_root}/deploy/versions.env" \
  --env-file "${project_root}/deploy/env/.env.example" \
  -f "${project_root}/docker/docker-compose.yml" \
  "$@"
