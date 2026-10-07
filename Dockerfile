# syntax=docker/dockerfile:1

# ---- Build stage: compile, test-free package, then split the fat jar into cacheable layers ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so source-only changes rebuild quickly.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/country-info-service.jar extract --layers --destination extracted

# ---- Runtime stage: JRE only, non-root, layered for small incremental pushes ----
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app

# Least- to most-frequently changing, so most deploys only push the small application layer.
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./

USER 10001:10001
EXPOSE 8080

# Size the heap from the container memory limit and crash fast on OOM so Kubernetes restarts us.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "-jar", "country-info-service.jar"]
