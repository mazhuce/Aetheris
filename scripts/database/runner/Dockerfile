# Build context is scripts/database/runner only. It contains no private config.
# Defaults pin reviewed official same-tag OCI indexes; build ARG overrides are outside this lock.
ARG MAVEN_IMAGE=docker.io/library/maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e
ARG JAVA_IMAGE=docker.io/library/eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
FROM ${JAVA_IMAGE}
WORKDIR /opt/runner
COPY --from=build /build/target/classes ./classes
COPY --from=build /build/target/dependency ./dependency
USER 10001:10001
ENTRYPOINT ["java", "-cp", "/opt/runner/classes:/opt/runner/dependency/*", "com.aetheris.database.DatabaseRunner"]
