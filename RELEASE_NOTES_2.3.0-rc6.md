# ЭВО.СНТ S3 2.3.0-rc6 — UX Preview

Исправляющий release candidate.

## Исправления относительно rc5

- исправлен OIDC logout через Keycloak при включённом CSP;
- `form-action` теперь разрешает HTTPS-переход к внешнему Identity Provider после `POST /logout`;
- обязательная CSRF-защита logout сохранена;
- сохранены исправления RC5 по CSRF-токену, корпоративному UI главной страницы и S3 Profiles.

Для Keycloak должен быть разрешён post logout redirect:
`https://s3-perf.ep-m.tn.tngrp.ru/*`.
