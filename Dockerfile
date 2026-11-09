FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests \
    && JAR_FILE=$(find target -maxdepth 1 -type f -name 's3-multipart-uploader-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*.original' -print -quit) \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" target/application.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 s3perf \
    && mkdir -p /app/config \
    && chown -R s3perf /app
COPY --from=build /workspace/target/application.jar /app/application.jar
USER s3perf
VOLUME ["/app/config"]
ENV S3_PERF_BOOTSTRAP_FILE=/app/config/bootstrap-settings.json
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
