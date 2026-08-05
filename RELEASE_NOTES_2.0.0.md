# S3 Performance Test Web 2.0.0

Release 2.0.0 consolidates the Web UI, external PostgreSQL persistence, Vault integration, distributed agents, scheduling, security and observability work.

## Highlights

- Web UI for configuring and running S3 performance tests.
- Multiple persistent S3 connection profiles with profile selection during test execution.
- External PostgreSQL for history, audit, schedules, baselines and profiles.
- External HashiCorp Vault Community Edition 2.0.x with Token and AppRole authentication.
- Optional Keycloak OIDC authentication and ADMIN, OPERATOR and VIEWER roles.
- PostgreSQL-backed security audit page and API.
- Persistent cron scheduler with duplicate-dispatch protection.
- Coordinator and autonomous distributed agent runtime.
- Mixed workload, multipart upload, baseline and regression analysis.
- Prometheus metrics, health probes and Grafana dashboard.
- Testcontainers integration tests for PostgreSQL and MinIO.
- CSRF protection, security headers, safer cookies and CycloneDX SBOM.

## Runtime requirements

- Java 21.
- External PostgreSQL reachable by the application.
- S3-compatible endpoint.
- Optional external Vault and Keycloak.

## Database upgrade

Flyway applies migrations V1 through V8 automatically. Back up the PostgreSQL database before starting 2.0.0 against an existing installation.

## Artifacts

- `s3-multipart-uploader-2.0.0.jar`
- Docker image built from the repository Dockerfile
- CycloneDX SBOM JSON generated during Maven CI
- Grafana dashboard and Prometheus scrape examples under `deploy/`
