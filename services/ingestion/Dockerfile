FROM docker.1panel.live/library/maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY scripts/maven-mirror-settings.xml ./settings.xml
COPY services/ingestion/pom.xml ./pom.xml
RUN mvn -s settings.xml -q -DskipTests dependency:go-offline
COPY services/ingestion/src ./src
RUN mvn -s settings.xml -q -DskipTests package

FROM docker.1panel.live/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
