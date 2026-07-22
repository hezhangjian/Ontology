#!/bin/sh
set -eu

minio_user=$(cat /run/secrets/minio_root_user)
minio_password=$(cat /run/secrets/minio_root_password)

mc alias set platform "${MINIO_ENDPOINT}" "${minio_user}" "${minio_password}"

for bucket in aip-attachments backups exports flink-checkpoints import-staging quality-quarantine raw; do
  mc mb --ignore-existing "platform/${bucket}"
  mc anonymous set none "platform/${bucket}"
done
