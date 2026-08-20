# ЭВО.СНТ S3 2.2.3-rc10

Release candidate 10 содержит исправления Web UI и настроек Keycloak.

## Изменения

- Исправлена работа кнопок «Проверить подключение» и «Сохранить» на странице настроек Keycloak: inline JavaScript заменён внешним `keycloak-settings.js`, совместимым с действующей Content Security Policy.
- Новый корпоративный UI и глобальный переключатель вариантов A/B/C/D применены к страницам ClickHouse Replication, HA Dashboard и Failover.
- Сохранено единое представление UI между разделами приложения через localStorage.
- Исправлена конфигурация CycloneDX Maven Plugin (`org.cyclonedx`) для корректной сборки и генерации SBOM.
- Версия приложения и Docker-артефакта обновлена до `2.2.3-rc10`.

## Проверки

Перед выпуском изменения проходят Maven CI, CodeQL и Dependency Review.
Release workflow повторно запущен отдельным push после исправления trigger-ветки на `release/2.2.3-rc10`.
