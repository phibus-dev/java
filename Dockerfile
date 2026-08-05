FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 uploader
COPY --from=build /workspace/target/s3-multipart-uploader-1.0.0-SNAPSHOT.jar /app/uploader.jar
USER uploader
ENTRYPOINT ["java", "-jar", "/app/uploader.jar"]
