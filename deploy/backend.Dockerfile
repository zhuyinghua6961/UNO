FROM maven:3.9.11-eclipse-temurin-17 AS build
ARG SERVICE
WORKDIR /workspace
COPY backend/ ./
RUN --mount=type=cache,id=uno-maven-cache,target=/root/.m2,sharing=locked \
    mvn -B -pl "$SERVICE" -am package -Dmaven.test.skip=true \
    && cp "$SERVICE/target/$SERVICE-0.1.0-SNAPSHOT.jar" /app.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 uno && useradd --uid 10001 --gid uno --no-create-home uno
COPY --from=build /app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
