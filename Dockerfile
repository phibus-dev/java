FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 uploader && mkdir -p /app/config && chown -R uploader /app
COPY --from=build /workspace/target/s3-multipart-uploader-1.3.0-SNAPSHOT.jar /app/application.jar
USER uploader
VOLUME ["/app/config"]
ENV S3_PERF_BOOTSTRAP_FILE=/app/config/bootstrap-settings.json
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
