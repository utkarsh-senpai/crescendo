# Crescendo game backend (Spring Boot) — Render free-tier deployment image.
#
# Multi-stage: build the fat jar with Maven, then run it on a slim JRE. Build context = backend/.
#   docker build -t crescendo-game backend/
#
# The build uses backend/maven-settings.xml (Central-only, no company Artifactory mirror) so it
# resolves cleanly off any machine / CI. Runtime JVM flags are tuned for Render's 512MB free box:
# cap the heap at 75% of the container limit and use SerialGC (lowest overhead on 0.1 CPU / 512MB).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependencies first: copy the pom + settings, go offline, then copy sources.
COPY pom.xml maven-settings.xml ./
RUN mvn -s maven-settings.xml -B dependency:go-offline
COPY src ./src
RUN mvn -s maven-settings.xml -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

# Container-aware heap + low-overhead GC for a 512MB / 0.1 CPU free instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Xss512k"
ENV SPRING_PROFILES_ACTIVE=prod

# Render injects $PORT at runtime; Spring reads SERVER_PORT. Default 8080 for local `docker run`.
ENV PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar app.jar"]
