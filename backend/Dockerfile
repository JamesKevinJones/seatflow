# Multi-stage, so the image ships a JRE and a jar rather than a JDK, Maven, and
# the whole dependency cache.

# ---------------------------------------------------------------- build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Dependencies first, in their own layer. They change far less often than the
# source, so an ordinary code change rebuilds from here without re-downloading
# the internet.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests are skipped here on purpose. They need a Docker daemon for
# Testcontainers, and running them inside the image build would mean
# docker-in-docker for no benefit - CI runs `mvnw verify` separately.
RUN ./mvnw -B -q clean package -DskipTests

# ------------------------------------------------------------------ run
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is not guaranteed in the JRE image and the healthcheck below needs it.
# Installed explicitly rather than assumed, and the apt lists are dropped in the
# same layer so they do not ship.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged. A process that never needs to write outside /tmp has no business
# running as root.
RUN groupadd --system seatflow && useradd --system --gid seatflow seatflow

COPY --from=build --chown=seatflow:seatflow /build/target/*.jar app.jar
USER seatflow

EXPOSE 8080

# Container-aware defaults: the JVM reads the cgroup limit rather than the
# host's memory, so a 512MB container does not try to size a heap for 32GB.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# Uses the readiness probe rather than a bare port check, so the container is
# only "healthy" once Flyway has migrated and the datasource is up.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health/readiness || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
