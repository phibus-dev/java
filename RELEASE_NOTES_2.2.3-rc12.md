# ЭВО.СНТ S3 2.2.3-rc12

Release candidate с исправлением авторизации через Keycloak Client Roles.

## Изменения

- Роли приложения читаются из `resource_access.<client-id>.roles`.
- Исправлен разбор Client ID: идентификатор клиента больше не переводится в верхний регистр и используется как регистрозависимый ключ Keycloak.
- Поддержаны роли `ADMIN`, `OPERATOR`, `VIEWER` и настраиваемые имена ролей.
- Client Roles используются как для интерактивного OIDC-входа, так и для Bearer JWT API.
- Сохранены исправления CSRF для кнопок проверки и сохранения настроек Keycloak из RC11.

## Keycloak

Для клиента `s3-perf` создайте Client Roles `ADMIN`, `OPERATOR`, `VIEWER` и назначьте необходимые роли пользователям через Role mapping.
