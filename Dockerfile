# syntax=docker/dockerfile:1
#
# Prompt Battle API.
#
# RUN EXACTLY ONE INSTANCE. The whole event (both cities) lives in memory and is snapshotted to DATA_DIR
# (SPEC §7.4). Two containers would split the game state, and two containers on one volume would overwrite
# each other's snapshot. On Railway: replicas = 1, serverless off, one volume mounted at /data.
#
# Build: docker build -t prompt-battle-api .   Run: docker run -p 8080:8080 --env-file .env -v "$PWD/data:/data" prompt-battle-api
# Tests run in CI (./mvnw -B verify); Railway deploys only after CI passes, so the image build skips them.

FROM eclipse-temurin:25.0.5.1_2-jdk-noble AS build
WORKDIR /src
# Dependencies first, so source changes reuse this layer.
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
    && cp target/prompt-battle-api-*.jar /app.jar

FROM eclipse-temurin:25.0.5.1_2-jre-noble
# The JVM runs as this unprivileged user. The entrypoint starts as root only to give it the mounted volume.
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app /data \
    && chown app:app /data
WORKDIR /app
COPY --from=build --chown=root:root /app.jar /app/app.jar
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh
ENV DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
