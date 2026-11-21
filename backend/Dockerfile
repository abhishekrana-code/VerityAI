# syntax=docker/dockerfile:1

# ---- build ---------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies against the POM first so source edits don't refetch them.
COPY pom.xml .
RUN mvn -B -q -Dmaven.test.skip=true dependency:go-offline

COPY src ./src
RUN mvn -B -q -Dmaven.test.skip=true package

# ---- runtime -------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# curl backs the compose healthcheck against /actuator/health/readiness.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN mkdir -p /var/lib/verityai/uploads /var/lib/verityai/models

COPY --from=build /build/target/verityai-backend-0.1.0-SNAPSHOT.jar /app/app.jar

# The compose files mount named volumes at these paths, so uploads and the
# ONNX embedding cache survive `docker compose down`.
ENV UPLOAD_DIR=/var/lib/verityai/uploads \
    EMBEDDINGS_CACHE_DIR=/var/lib/verityai/models \
    SERVER_PORT=8080

EXPOSE 8080

# Container-aware heap sizing; the rest of the JVM defaults are fine.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
