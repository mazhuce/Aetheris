FROM docker.1panel.live/library/maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY scripts/maven-mirror-settings.xml ./settings.xml
COPY services/ai-agent/pom.xml ./pom.xml
RUN mvn -s settings.xml -q -DskipTests dependency:go-offline
COPY scripts/HealthCheck.java ./HealthCheck.java
RUN javac HealthCheck.java
COPY services/ai-agent/src ./src
RUN mvn -s settings.xml -q -DskipTests package

FROM docker.1panel.live/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
COPY --from=build /workspace/HealthCheck.class HealthCheck.class
EXPOSE 8086
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=4 CMD ["java", "HealthCheck", "http://127.0.0.1:8086/actuator/health"]
ENTRYPOINT ["java", "-jar", "app.jar"]
