ARG FLINK_BASE_IMAGE=flink:1.20.5-scala_2.12-java17
FROM ${FLINK_BASE_IMAGE}

USER root

ARG FLINK_CONNECTOR_JDBC_SHA256
ARG FLINK_CONNECTOR_JDBC_VERSION
ARG FLINK_CONNECTOR_KAFKA_SHA256
ARG FLINK_CONNECTOR_KAFKA_VERSION
ARG FLINK_CONNECTOR_PULSAR_SHA256
ARG FLINK_CONNECTOR_PULSAR_VERSION
ARG FLINK_S3_HADOOP_SHA256
ARG FLINK_S3_HADOOP_VERSION
ARG MYSQL_DRIVER_SHA256
ARG MYSQL_DRIVER_VERSION
ARG POSTGRES_DRIVER_SHA256
ARG POSTGRES_DRIVER_VERSION

RUN set -eux; \
    base=https://repo1.maven.org/maven2; \
    curl -fsSL "${base}/org/apache/flink/flink-connector-jdbc/${FLINK_CONNECTOR_JDBC_VERSION}/flink-connector-jdbc-${FLINK_CONNECTOR_JDBC_VERSION}.jar" -o /opt/flink/lib/flink-connector-jdbc.jar; \
    echo "${FLINK_CONNECTOR_JDBC_SHA256}  /opt/flink/lib/flink-connector-jdbc.jar" | sha256sum -c -; \
    curl -fsSL "${base}/org/apache/flink/flink-connector-kafka/${FLINK_CONNECTOR_KAFKA_VERSION}/flink-connector-kafka-${FLINK_CONNECTOR_KAFKA_VERSION}.jar" -o /opt/flink/lib/flink-connector-kafka.jar; \
    echo "${FLINK_CONNECTOR_KAFKA_SHA256}  /opt/flink/lib/flink-connector-kafka.jar" | sha256sum -c -; \
    curl -fsSL "${base}/org/apache/flink/flink-connector-pulsar/${FLINK_CONNECTOR_PULSAR_VERSION}/flink-connector-pulsar-${FLINK_CONNECTOR_PULSAR_VERSION}.jar" -o /opt/flink/lib/flink-connector-pulsar.jar; \
    echo "${FLINK_CONNECTOR_PULSAR_SHA256}  /opt/flink/lib/flink-connector-pulsar.jar" | sha256sum -c -; \
    curl -fsSL "${base}/org/postgresql/postgresql/${POSTGRES_DRIVER_VERSION}/postgresql-${POSTGRES_DRIVER_VERSION}.jar" -o /opt/flink/lib/postgresql.jar; \
    echo "${POSTGRES_DRIVER_SHA256}  /opt/flink/lib/postgresql.jar" | sha256sum -c -; \
    curl -fsSL "${base}/com/mysql/mysql-connector-j/${MYSQL_DRIVER_VERSION}/mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar" -o /opt/flink/lib/mysql-connector-j.jar; \
    echo "${MYSQL_DRIVER_SHA256}  /opt/flink/lib/mysql-connector-j.jar" | sha256sum -c -; \
    mkdir -p /opt/flink/plugins/s3-fs-hadoop; \
    curl -fsSL "${base}/org/apache/flink/flink-s3-fs-hadoop/${FLINK_S3_HADOOP_VERSION}/flink-s3-fs-hadoop-${FLINK_S3_HADOOP_VERSION}.jar" -o /opt/flink/plugins/s3-fs-hadoop/flink-s3-fs-hadoop.jar; \
    echo "${FLINK_S3_HADOOP_SHA256}  /opt/flink/plugins/s3-fs-hadoop/flink-s3-fs-hadoop.jar" | sha256sum -c -

COPY docker/images/flink/docker-entrypoint-wrapper.sh /docker-entrypoint-wrapper.sh
RUN chmod 0555 /docker-entrypoint-wrapper.sh

USER flink
ENTRYPOINT ["/docker-entrypoint-wrapper.sh"]
