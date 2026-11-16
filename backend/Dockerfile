# syntax=docker/dockerfile:1
# Backend image. Build from the repository root:
#   docker build -f backend/Dockerfile -t elevate/load-tester .

# ---- Build stage: Maven (through the wrapper) on a full JDK -------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY mock-target/pom.xml mock-target/
COPY backend/pom.xml backend/
COPY mock-target/src mock-target/src
COPY backend/src backend/src
# The Maven repository is a cache mount, so rebuilds do not download everything again.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl backend -am package -DskipTests

# ---- Runtime stage: JRE only, non-root user -----------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app \
    && mkdir /data && chown app:app /data
WORKDIR /app
COPY --from=build /src/backend/target/load-tester.jar app.jar
USER app
# The H2 database lives in /data (mount a volume there to keep the run history).
ENV DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
