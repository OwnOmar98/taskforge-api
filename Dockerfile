# --- Build stage: needs the full JDK plus Maven, none of which belong in
# the image that actually runs in production. ---
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Dependencies first, source last: as long as pom.xml is unchanged, Docker
# reuses this layer's cache even when application code changes, so a normal
# code-only rebuild skips re-downloading the entire dependency tree.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

COPY src ./src
# Tests are deliberately not run here: they need Testcontainers, which needs
# a Docker daemon this build stage doesn't have access to, and CI's own test
# job (a separate, required step before this image is ever built) already
# covers them - running them again here would just be redundant and unable
# to work regardless.
RUN ./mvnw package -DskipTests -B

# --- Runtime stage: only a JRE and the built jar, nothing the build needed. ---
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Runs as its own unprivileged user, not root - the default in most base
# images and not appropriate for a process accepting network input.
# --chown at copy time, not a separate RUN chown afterward: chowning a large
# file in its own layer duplicates the whole file on disk (the previous
# layer still has the root-owned copy) - COPY --chown never creates that
# first copy at all.
RUN addgroup -S taskforge && adduser -S taskforge -G taskforge
COPY --from=build --chown=taskforge:taskforge /workspace/target/taskforge-api-*.jar app.jar
USER taskforge

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
