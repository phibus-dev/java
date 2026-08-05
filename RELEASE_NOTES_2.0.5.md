# S3 Performance Test Web 2.0.5

Patch release correcting bucket discovery from the Web UI.

## Fixed

- `POST /api/buckets` no longer validates the full load-test request.
- Added a dedicated `BucketListRequest` containing only S3 connection parameters.
- The bucket field may remain empty while the application requests the available bucket list.
- The strict `@NotBlank` validation for bucket remains enabled for actual test execution through `POST /api/tests`.
- S3 profile selection through `profileId`, manual credentials, endpoint, region and path-style access remain supported.

## Included fixes from earlier 2.0.x patches

- bootstrap startup without a preconfigured PostgreSQL datasource;
- conditional Flyway execution after PostgreSQL configuration;
- CSRF handling on `/settings`;
- shared CSRF handling for the main Web UI.

## Upgrade

Replace the previous JAR or container image with version `2.0.5`. No database schema migration is introduced by this patch.
