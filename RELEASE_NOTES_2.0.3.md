# S3 Performance Test Web 2.0.3

Patch release fixing CSRF handling on the bootstrap settings page.

## Fixed

- Fixed `403 Forbidden` responses from the Web UI when testing PostgreSQL connectivity.
- Fixed `403 Forbidden` responses when testing Vault connectivity.
- Fixed saving bootstrap settings from `/settings` while CSRF protection is enabled.
- Added the CSRF token and header name to the Thymeleaf settings page.
- Added the CSRF header and same-origin credentials to all settings POST requests.

## Upgrade

Replace the 2.0.2 JAR or container image with 2.0.3. No PostgreSQL schema changes are included in this release.

## Verification

1. Start the application with Keycloak disabled.
2. Open `/settings`.
3. Enter PostgreSQL connection parameters.
4. Click **Проверить PostgreSQL**.
5. Confirm that `POST /api/settings/test/postgresql` no longer returns HTTP 403.
6. Verify saving settings and testing Vault in the same way.
