# 说明：FROM 使用国内镜像前缀；若网络可直连 Docker Hub，可改回 docker.io/library/...。
# build stage
FROM docker.1panel.live/library/maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY scripts/maven-mirror-settings.xml ./settings.xml
COPY services/metadata/pom.xml ./pom.xml
RUN mvn -s settings.xml -q -DskipTests dependency:go-offline
COPY services/metadata/src ./src
RUN mvn -s settings.xml -q -DskipTests package

# runtime stage
FROM docker.1panel.live/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
