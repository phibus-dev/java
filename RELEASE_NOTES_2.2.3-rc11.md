# ЭВО.СНТ S3 2.2.3-rc11

Release candidate 11 содержит исправление проверки и сохранения настроек Keycloak.

## Изменения

- Исправлен `403 Forbidden` при вызове `/api/settings/keycloak/test` и `/api/settings/keycloak`.
- CSRF-токен и имя CSRF-заголовка теперь передаются странице Keycloak через meta-теги Spring Security.
- `keycloak-settings.js` использует meta-токен с fallback на cookie `XSRF-TOKEN` и отправляет запросы с `credentials: same-origin`.
- Сохранены исправления корпоративного UI для Keycloak, Replication, HA Dashboard и Failover из предыдущего RC.
- Версия приложения и Docker-артефакта обновлена до `2.2.3-rc11`.

## Проверки

Перед публикацией выполняются Maven CI, CodeQL и Dependency Review.
