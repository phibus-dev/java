# ЭВО.СНТ S3 2.3.0-rc3 — UX Preview

Исправляющий release candidate UX Preview.

## Исправления относительно rc2

- исправлено формирование OAuth2 `redirect_uri` при публикации приложения через HTTPS reverse proxy/HAProxy;
- Spring Boot теперь учитывает `X-Forwarded-Proto`, `X-Forwarded-Host` и `X-Forwarded-Port` (`server.forward-headers-strategy=framework`);
- внешний callback Keycloak формируется с протоколом HTTPS вместо внутреннего HTTP;
- версия Maven и Docker-артефакта синхронизирована на 2.3.0-rc3.

Ожидаемый callback для текущей схемы публикации:
`https://s3-perf.ep-m.tn.tngrp.ru/login/oauth2/code/keycloak`.

## UX Preview

Сохраняются изменения предыдущих RC: титульная страница, навигация, пользовательская панель и выход, favicon/web manifest, breadcrumbs, поиск по истории, toast-уведомления, role-aware UI, состояние сервисов и варианты UI A/B/C/D.
