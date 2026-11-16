# syntax=docker/dockerfile:1
# Mock target image. Build from the repository root:
#   docker build -f mock-target/Dockerfile -t elevate/mock-target .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY mock-target/pom.xml mock-target/
COPY backend/pom.xml backend/
COPY mock-target/src mock-target/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl mock-target package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=build /src/mock-target/target/mock-target-1.0.0-exec.jar mock.jar
USER app
# Listen on every interface so other containers can reach it as http://mock:8000.
ENV MOCK_HOST=0.0.0.0 MOCK_PORT=8000
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/mock.jar"]
