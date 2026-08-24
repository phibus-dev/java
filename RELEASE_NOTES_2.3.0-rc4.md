# ЭВО.СНТ S3 2.3.0-rc4 — UX Preview

Исправляющий release candidate UX Preview.

## Исправления относительно rc3

- исправлен выход из приложения при включённой Keycloak/OIDC-аутентификации;
- logout теперь выполняется как навигационный POST и передаётся в Keycloak через `OidcClientInitiatedLogoutSuccessHandler`;
- завершается локальная Spring Security session и инициируется завершение OIDC-сессии Keycloak;
- страница `/settings/s3-profiles` полностью переведена на корпоративный UX/layout;
- добавлены общая навигация, breadcrumbs, favicon, пользовательское меню и переключатель вариантов представления;
- inline CSS/JavaScript страницы S3 Profiles удалены; клиентская логика вынесена в `/s3-profiles.js`, что соответствует CSP `script-src 'self'`;
- сохранены исправления HTTPS forwarded headers и servlet startup из rc3.

## Keycloak logout

Для возврата пользователя в приложение после завершения SSO-сессии в клиенте `s3-perf` необходимо разрешить:

`Valid post logout redirect URIs: https://s3-perf.ep-m.tn.tngrp.ru/*`

Основной redirect URI остаётся:

`https://s3-perf.ep-m.tn.tngrp.ru/login/oauth2/code/keycloak`
