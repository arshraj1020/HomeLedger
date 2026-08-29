# Multi-stage build: compile with Maven + JDK 21, run on a slim JRE.

# --------------------------------------------------------------- build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# pom.xml binds maven-toolchains-plugin to the `validate` phase, so every
# lifecycle invocation needs a JDK 21 toolchain declared on disk. On a
# developer's machine that is ~/.m2/toolchains.xml (see toolchains.xml.example);
# here it is generated from the base image's own JDK.
#
# Without this the image build fails at `validate` with "Cannot find matching
# toolchain definitions for the following toolchain types: jdk [version '21']"
# — before compiling a single class, and with an error that reads like a
# missing JDK rather than a missing config file.
RUN mkdir -p /root/.m2 && printf '%s\n' \
      '<?xml version="1.0" encoding="UTF-8"?>' \
      '<toolchains>' \
      '  <toolchain>' \
      '    <type>jdk</type>' \
      '    <provides><version>21</version></provides>' \
      "    <configuration><jdkHome>${JAVA_HOME}</jdkHome></configuration>" \
      '  </toolchain>' \
      '</toolchains>' > /root/.m2/toolchains.xml

# Dependency resolution as its own layer, so a source-only change does not
# re-download the world.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src

# Tests are not run in the image build: the integration suite needs a Docker
# daemon for Testcontainers, which is not available inside a build. CI runs the
# full suite against a real Postgres (.github/workflows/ci.yml) — the image is
# built from a commit those tests have already passed on.
RUN mvn -B -ntp -DskipTests package \
 && cp target/household-ledger-*.jar /build/app.jar

# ------------------------------------------------------------- runtime stage
FROM eclipse-temurin:21-jre-jammy

# curl is here only so the container can answer HEALTHCHECK and so
# docker-compose's `depends_on: condition: service_healthy` works for the app.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Unprivileged, no login shell, no home write beyond /app.
RUN groupadd --system app && useradd --system --gid app --no-create-home app

WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar app.jar
USER app

# No secrets in the image. Everything the application needs at runtime —
# DB_USER, DB_PASSWORD, JWT_SECRET — arrives from the environment at
# `docker run` time. Nothing here is baked in.
ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# Hits the anonymous health endpoint. show-details is "when-authorized", so an
# unauthenticated probe gets {"status":"UP"} and no component detail.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/actuator/health" || exit 1

# exec, so java is PID 1 and receives SIGTERM directly — which is what makes
# `server.shutdown: graceful` actually drain in-flight requests on a rolling
# deploy instead of being killed by the shell.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
