FROM eclipse-temurin:17-jre-jammy@sha256:e85989f3e4d136b3d7dde921e157fddb9c7016805a225c1ec483326b825b3ca5
ARG SERVICE
WORKDIR /app
RUN groupadd --gid 10001 uno && useradd --uid 10001 --gid uno --no-create-home uno
COPY backend/${SERVICE}.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
