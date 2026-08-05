# Upgrade guide to 2.0.0

## 1. Back up configuration and PostgreSQL

Back up the bootstrap settings file and the external PostgreSQL database before upgrading.

## 2. Verify required environment

- Java 21 is installed for JAR deployments.
- External PostgreSQL is reachable.
- The application account can create and alter objects required by Flyway.
- Vault and Keycloak endpoints are reachable when those integrations are enabled.

## 3. Replace the application artifact

Replace the previous JAR with `s3-multipart-uploader-2.0.0.jar`, or rebuild the Docker image from the 2.0.0 source/tag.

## 4. Review renamed artifact paths

The release JAR no longer contains `SNAPSHOT` in its name. Update service units, scripts and container build references accordingly.

## 5. Start the application

At startup, Flyway validates and applies migrations through V8. Do not run multiple upgrade instances simultaneously against the same database during the first start.

## 6. Validate bootstrap mode and normal mode

Check:

- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/settings`

After PostgreSQL is available, verify normal pages, history, schedules, S3 profiles and audit.

## 7. Security validation

When Keycloak is enabled, verify ADMIN, OPERATOR and VIEWER access separately. For HTTPS deployments set:

```bash
S3PERF_SESSION_COOKIE_SECURE=true
```

## 8. Operational validation

Run one local test and one distributed test, confirm PostgreSQL history, Prometheus metrics, Grafana panels and scheduler recovery after an application restart.
