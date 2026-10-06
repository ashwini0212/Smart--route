# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with the Maven wrapper ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
# Copy build descriptors first so dependency downloads are cached until a pom.xml changes.
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY algorithms/pom.xml algorithms/pom.xml
COPY app/pom.xml app/pom.xml
# The reactor lists every module, so every module's pom must exist even though only `app` is built.
COPY benchmarks/pom.xml benchmarks/pom.xml
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl app -am dependency:go-offline -DexcludeReactor=true || true
COPY algorithms/src algorithms/src
COPY app/src app/src
# Tests run in CI (and locally with ./mvnw verify); the image build only packages the app and what it needs.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl app -am package -DskipTests
# Split the fat jar into layers so code changes don't invalidate the dependency layer.
RUN java -Djarmode=tools -jar app/target/smartroute-app.jar extract --layers --launcher --destination extracted

# ---- Runtime stage: JRE only, non-root user ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S smartroute && adduser -S smartroute -G smartroute
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER smartroute
EXPOSE 8080
# Respect container memory limits instead of host memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
