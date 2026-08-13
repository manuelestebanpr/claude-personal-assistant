# --- Build stage ---
# Images are fully qualified: podman refuses to resolve short names without a
# containers-registries.conf(5), while docker silently assumes Docker Hub.
FROM docker.io/library/maven:3-eclipse-temurin-25 AS build
WORKDIR /build

# Cache the dependency layer separately from the sources.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# --- Runtime stage ---
FROM docker.io/library/eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

# File-based H2 database lives here; mount it to survive container replacement.
VOLUME /app/data

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
