FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests \
    && mvn -Pflink-job package -DskipTests

FROM eclipse-temurin:25-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/ontology.jar /app/ontology.jar
COPY --from=build /workspace/target/ontology-flink-job.jar /app/ontology-flink-job.jar

EXPOSE 4242

ENTRYPOINT ["java", "-jar", "/app/ontology.jar"]
