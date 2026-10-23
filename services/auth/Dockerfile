FROM docker.io/library/maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /workspace
COPY scripts/maven-mirror-settings.xml ./settings.xml
COPY libs/security-protocol/pom.xml ./security-protocol/pom.xml
COPY libs/security-protocol/src/main ./security-protocol/src/main
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -s settings.xml -q -f security-protocol/pom.xml -Dmaven.test.skip=true install
COPY libs/security-servlet/pom.xml ./security-servlet/pom.xml
COPY libs/security-servlet/src/main ./security-servlet/src/main
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -s settings.xml -q -f security-servlet/pom.xml -Dmaven.test.skip=true install
COPY services/auth/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -s settings.xml -q -DskipTests dependency:go-offline
COPY scripts/HealthCheck.java ./HealthCheck.java
RUN javac HealthCheck.java
COPY services/auth/src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -s settings.xml -q -DskipTests package

FROM docker.io/library/eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1"
COPY --from=build /workspace/target/*.jar app.jar
COPY --from=build /workspace/HealthCheck.class HealthCheck.class
EXPOSE 8087
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=4 CMD ["java", "HealthCheck", "http://127.0.0.1:8087/actuator/health"]
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
