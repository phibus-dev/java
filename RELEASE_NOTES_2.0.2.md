# S3 Performance Test Web 2.0.2

Patch release correcting bootstrap startup when PostgreSQL has not yet been configured.

## Fixed

- Disabled Spring Boot Flyway auto-configuration during bootstrap startup.
- Prevented the auto-configured `flywayInitializer` from opening the bootstrap DataSource before `/settings` is available.
- Preserved database migrations through the application's own conditional Flyway runner.
- First startup now works without `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` or `SPRING_DATASOURCE_PASSWORD`.

## First startup

```bash
export S3_PERF_BOOTSTRAP_KEY='long-random-secret'
java -jar s3-performance-test-web-2.0.2.jar
```

Open:

```text
http://localhost:8080/settings
```

Configure the external PostgreSQL connection, save the settings and restart the application. Flyway migrations are then executed by `BootstrapJdbcConfiguration`.

## Upgrade from 2.0.1

Replace the JAR or Docker image. No manual database migration is required before startup.
