# S3 Performance Test Web 2.0.1

Patch release correcting the first-start bootstrap flow introduced in 2.0.0.

## Fixed

- the executable JAR now starts without a preconfigured `spring.datasource.url`;
- first startup correctly enters bootstrap mode and exposes `/settings` and health endpoints;
- PostgreSQL settings continue to be loaded from the encrypted bootstrap store;
- `JdbcTemplate` and transaction support are provided by the application without Spring Boot requiring an embedded or preconfigured DataSource;
- Flyway runs only after external PostgreSQL has been configured;
- scheduler database polling is skipped while the application is not in `READY` state;
- security audit persistence remains non-blocking when PostgreSQL is not configured or temporarily unavailable.

## Upgrade from 2.0.0

Replace the 2.0.0 JAR or container image with 2.0.1. No database migration is required specifically for this patch release.

First startup can be performed with only the bootstrap encryption key:

```bash
export S3_PERF_BOOTSTRAP_KEY='long-random-secret'
java -jar s3-multipart-uploader-2.0.1.jar
```

Then open:

```text
http://localhost:8080/settings
```

## Artifacts

The release workflow produces:

- executable JAR;
- source JAR;
- Javadoc JAR;
- CycloneDX SBOM;
- SHA256 checksums;
- container image `ghcr.io/phibus-dev/s3-performance-test-web:2.0.1`.
