ARG JAVA_IMAGE=eclipse-temurin:21.0.7_6-jre-jammy
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21
FROM ${MAVEN_IMAGE} AS agent

ARG SKYWALKING_JAVA_AGENT_SHA512
ARG SKYWALKING_JAVA_AGENT_VERSION
RUN --mount=type=cache,target=/tmp/skywalking-download \
    archive="/tmp/skywalking-download/apache-skywalking-java-agent-${SKYWALKING_JAVA_AGENT_VERSION}.tgz" && \
    if [ ! -f "${archive}" ]; then \
      curl -fsSL "https://archive.apache.org/dist/skywalking/java-agent/${SKYWALKING_JAVA_AGENT_VERSION}/apache-skywalking-java-agent-${SKYWALKING_JAVA_AGENT_VERSION}.tgz" -o "${archive}"; \
    fi && \
    echo "${SKYWALKING_JAVA_AGENT_SHA512}  ${archive}" | sha512sum -c - && \
    mkdir -p /tmp/skywalking && \
    tar -xzf "${archive}" -C /tmp/skywalking --strip-components=1

FROM ${MAVEN_IMAGE} AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY backend backend
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw package -DskipTests

FROM ${JAVA_IMAGE}

ARG MODULE
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build "/workspace/backend/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar" /app/app.jar
COPY --from=build /workspace/backend/flink-job/target/flink-job-0.1.0-SNAPSHOT.jar /app/flink-job.jar
COPY --from=agent /tmp/skywalking/skywalking-agent.jar /opt/skywalking/skywalking-agent.jar
COPY --from=agent /tmp/skywalking/config /opt/skywalking/config
COPY --from=agent /tmp/skywalking/plugins /opt/skywalking/plugins

ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/skywalking/skywalking-agent.jar -Xms128m -Xmx384m"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
