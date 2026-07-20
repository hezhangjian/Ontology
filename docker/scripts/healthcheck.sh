#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
compose="${project_root}/docker/scripts/compose.sh"

healthy_services="agent-runtime apisix bff flink-jobmanager flink-taskmanager frontend hugegraph keycloak maintenance-runner minio ontology-core opensearch otel-collector postgres pulsar skywalking-oap skywalking-ui"

for service in ${healthy_services}; do
  container_id=$(${compose} --profile '*' ps -q "${service}")
  if [ -z "${container_id}" ]; then
    echo "${service}: missing" >&2
    exit 1
  fi

  status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")
  if [ "${status}" != healthy ] && [ "${status}" != running ]; then
    echo "${service}: ${status}" >&2
    exit 1
  fi
  echo "${service}: ${status}"
done

for job in minio-bootstrap pulsar-bootstrap; do
  container_id=$(${compose} --profile '*' ps -aq "${job}")
  exit_code=$(docker inspect --format '{{.State.ExitCode}}' "${container_id}")
  status=$(docker inspect --format '{{.State.Status}}' "${container_id}")
  if [ "${status}" != exited ] || [ "${exit_code}" != 0 ]; then
    echo "${job}: ${status} (exit ${exit_code})" >&2
    exit 1
  fi
  echo "${job}: completed"
done
