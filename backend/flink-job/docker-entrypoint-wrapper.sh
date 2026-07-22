#!/bin/sh
set -eu

if [ -f /run/secrets/minio_root_user ]; then
  export AWS_ACCESS_KEY_ID="$(cat /run/secrets/minio_root_user)"
fi
if [ -f /run/secrets/minio_root_password ]; then
  export AWS_SECRET_ACCESS_KEY="$(cat /run/secrets/minio_root_password)"
fi

exec /docker-entrypoint.sh "$@"
