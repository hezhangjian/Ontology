#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/docker/scripts/compose.sh"

. "${project_root}/docker/versions.env"

case $(docker info --format '{{.Architecture}}') in
  aarch64) runtime_arch=arm64 ;;
  x86_64) runtime_arch=amd64 ;;
  *) runtime_arch=$(docker info --format '{{.Architecture}}') ;;
esac

images="${APISIX_IMAGE} ${FLINK_BASE_IMAGE} ${HUGEGRAPH_IMAGE} ${JAVA_IMAGE} ${MAVEN_IMAGE} ${MINIO_IMAGE} ${MINIO_MC_IMAGE} ${NGINX_IMAGE} ${NODE_IMAGE} ${OPENSEARCH_IMAGE} ${OTEL_COLLECTOR_IMAGE} ${POSTGRES_IMAGE} ${PULSAR_IMAGE} ${SKYWALKING_OAP_IMAGE} ${SKYWALKING_UI_IMAGE}"

for image in ${images}; do
  case ${image} in
    *@sha256:*) ;;
    *) echo "Image is not digest locked: ${image}" >&2; exit 1 ;;
  esac
  arch=$(docker image inspect --format '{{.Architecture}}' "${image}")
  if [ "${arch}" != "${runtime_arch}" ]; then
    echo "Architecture mismatch for ${image}: ${arch}, expected ${runtime_arch}" >&2
    exit 1
  fi
done

${compose} --profile '*' exec -T flink-jobmanager sh -ec "
  echo '${FLINK_CONNECTOR_JDBC_SHA256}  /opt/flink/lib/flink-connector-jdbc.jar' | sha256sum -c -
  echo '${FLINK_CONNECTOR_KAFKA_SHA256}  /opt/flink/lib/flink-connector-kafka.jar' | sha256sum -c -
  echo '${FLINK_CONNECTOR_PULSAR_SHA256}  /opt/flink/lib/flink-connector-pulsar.jar' | sha256sum -c -
  echo '${MYSQL_DRIVER_SHA256}  /opt/flink/lib/mysql-connector-j.jar' | sha256sum -c -
  echo '${POSTGRES_DRIVER_SHA256}  /opt/flink/lib/postgresql.jar' | sha256sum -c -
  echo '${FLINK_S3_HADOOP_SHA256}  /opt/flink/plugins/s3-fs-hadoop/flink-s3-fs-hadoop.jar' | sha256sum -c -
"

echo "Compatibility checks passed for ${runtime_arch}."
