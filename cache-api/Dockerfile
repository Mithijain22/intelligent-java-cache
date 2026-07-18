# Multi-stage build: compile with a full JDK+Maven image, run on a slim JRE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy poms first so dependency resolution is cached across builds when only
# source files change.
COPY pom.xml .
COPY cache-engine/pom.xml cache-engine/pom.xml
COPY cache-api/pom.xml cache-api/pom.xml
RUN mvn -B dependency:go-offline -pl cache-engine,cache-api

COPY cache-engine cache-engine
COPY cache-api cache-api
RUN mvn -B clean package -pl cache-engine,cache-api -am -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/cache-api/target/cache-api-*.jar app.jar

# Persistence snapshots need a writable volume -- see application.yml's
# cache.persistence.path (defaults to ./data/cache-snapshot.json).
RUN mkdir -p /app/data
VOLUME /app/data

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
