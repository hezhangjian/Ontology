# P01 image and artifact locks

## Locking method

`deploy/versions.env` is the executable lock manifest. External runtime and build images use an exact tag plus OCI index digest. `deploy/scripts/compatibility-check.sh` then verifies that Docker resolved each lock to an image matching the current engine architecture.

Project-owned images are versioned as `0.1.0` and built from the locked Maven, Java, Node.js, Nginx, and Flink inputs. They are not represented as release-ready registry digests until P17.

## External images

| Variable | Locked version |
|---|---|
| `APISIX_IMAGE` | `apache/apisix:3.17.0-debian@sha256:6cbf65f3085d1386bfd636b7e88400c163c3641841909e674af7896a5766b092` |
| `FLINK_BASE_IMAGE` | `flink:1.20.5-scala_2.12-java17@sha256:5a781c3a3bf694d4befba7709fbc192403a461c2d852fed533587a0970e03f5e` |
| `HUGEGRAPH_IMAGE` | `hugegraph/hugegraph:1.7.0@sha256:077b89e1f2d5e228d99598bdb537161fb2e8a7b5abedf4724d97cf4de11529ba` |
| `JAVA_IMAGE` | `eclipse-temurin:21.0.7_6-jre-jammy@sha256:c4e6542e774de504da9b4729ff8d761287c965c1d788528ca78da30024efdb23` |
| `KEYCLOAK_IMAGE` | `quay.io/keycloak/keycloak:26.6.0@sha256:b0e5dbced1775de4d629f103c0a9cfc057decc62ce8d3cb1c54f8849a6c6eb62` |
| `MAVEN_IMAGE` | `maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e` |
| `MINIO_IMAGE` | `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` |
| `MINIO_MC_IMAGE` | `minio/mc:RELEASE.2025-08-13T08-35-41Z@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727` |
| `NGINX_IMAGE` | `nginx:1.27.0-alpine3.19@sha256:208b70eefac13ee9be00e486f79c695b15cef861c680527171a27d253d834be9` |
| `NODE_IMAGE` | `node:22.22.0-alpine3.23@sha256:e4bf2a82ad0a4037d28035ae71529873c069b13eb0455466ae0bc13363826e34` |
| `OPENSEARCH_IMAGE` | `opensearchproject/opensearch:3.7.0@sha256:44ba7ea58a319adf61c33ab16873f9ef5dbb30b291a832d375172f0b2d24e3c9` |
| `OTEL_COLLECTOR_IMAGE` | `otel/opentelemetry-collector-contrib:0.153.0@sha256:93aad750175cbf1a973ae1c5886c3371f4d800f61be25cdd26870b8441ffe9fa` |
| `POSTGRES_IMAGE` | `postgres:17.9-alpine3.23@sha256:c7526c0f6c3f30260a563d7bcf8ad778effac59a44f8ffa86678c35418338609` |
| `PULSAR_IMAGE` | `apachepulsar/pulsar:4.0.12@sha256:ff64bce17db6f667e78dc6b98c7faaf55cb500f65fd6ea594adf2454e450452a` |
| `SKYWALKING_OAP_IMAGE` | `apache/skywalking-oap-server:10.4.0@sha256:091d3a7f7ed5f3ec6fb43c2728cafedbe01372c63c7913a1332d51da581f8234` |
| `SKYWALKING_UI_IMAGE` | `apache/skywalking-ui:10.4.0@sha256:7103481938b4b27e5fc22e4693675fd301c5f5293438e8aebcb29e97ca9fed68` |

The listed values are multi-platform index digests. Runtime architecture resolution is an explicit gate rather than a host-specific lock, so the same manifest supports both Linux amd64 and arm64 deployment.

## Flink artifacts

| Artifact | Version | SHA-256 |
|---|---:|---|
| Flink JDBC connector | `3.3.0-1.20` | `50d243cf0e1aac67c2c81c9fb2e9b32bb7d7124b9594df009efa8122e3df1c75` |
| Flink Kafka connector | `3.4.0-1.20` | `0deab094f7f54df9e8cb7c77c15b4392e1f9923f98f8972b4346f6ff384bf5e6` |
| Flink Pulsar connector | `4.2.0-1.20` | `e57026a9578fe02e12cc7f35f300d1466ee7634a12d8f12f14bdd58977aa54b1` |
| Flink S3 Hadoop plugin | `1.20.5` | `6fcda3e7fedfb0ce11d0b6700155ca9d7e0b68475bdff2203c056a9fabeeb792` |
| MySQL JDBC driver | `9.4.0` | `49ed93c8b2bea9cb0929b85a8a28837b191d0f8eac6919fdcef16e36e2cd53b3` |
| PostgreSQL JDBC driver | `42.7.7` | `157963d60ae66d607e09466e8c0cdf8087e9cb20d0159899ffca96bca2528460` |

The custom Flink image downloads these artifacts during build, verifies every hash, and stores no unpinned runtime download logic.

## Known constraints

- MinIO is the frozen public image required by D-012. It predates later community security revisions and remains private to the Compose network.
- OpenSearch security is disabled for the P01 internal network. Public exposure is prohibited; P16 owns the production hardening gate.
- SkyWalking 10.4.0 uses the isolated `skywalking` namespace in OpenSearch 3.7.0. The P01 runtime gate must prove OAP readiness and keep telemetry storage separate from future object indices.
