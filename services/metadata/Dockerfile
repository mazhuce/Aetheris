# 说明：FROM 使用国内镜像前缀；若网络可直连 Docker Hub，可改回 docker.io/library/...。
# build stage
FROM docker.1panel.live/library/maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY scripts/maven-mirror-settings.xml ./settings.xml
COPY services/metadata/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -s settings.xml -q -DskipTests dependency:go-offline
COPY scripts/HealthCheck.java ./HealthCheck.java
RUN javac HealthCheck.java
COPY services/metadata/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -s settings.xml -q -DskipTests package

# runtime stage
FROM docker.1panel.live/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
COPY --from=build /workspace/HealthCheck.class HealthCheck.class
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=4 CMD ["java", "HealthCheck", "http://127.0.0.1:8082/actuator/health"]
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
