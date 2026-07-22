#!/bin/bash
set -euo pipefail

config=/hugegraph-server/conf/gremlin-server.yaml
sed -i 's/^#host: 127\.0\.0\.1$/host: 0.0.0.0/' "${config}"
exec ./docker-entrypoint.sh
