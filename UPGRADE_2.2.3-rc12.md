# Обновление до 2.2.3-rc12

1. Установите артефакт или Docker image версии `2.2.3-rc12`.
2. В Keycloak откройте `Clients -> s3-perf -> Roles` и создайте Client Roles `ADMIN`, `OPERATOR`, `VIEWER`.
3. Назначьте пользователям необходимые client roles через `Users -> Role mapping -> Assign role -> Filter by clients -> s3-perf`.
4. В настройках ЭВО.СНТ укажите Client ID `s3-perf` и сохраните настройки Keycloak.
5. Перезапустите приложение после изменения bootstrap-настроек Keycloak.
6. Завершите старую сессию Keycloak и войдите заново, чтобы новый access token содержал `resource_access.s3-perf.roles`.

Ожидаемый фрагмент access token:

```json
"resource_access": {
  "s3-perf": {
    "roles": ["ADMIN"]
  }
}
```
