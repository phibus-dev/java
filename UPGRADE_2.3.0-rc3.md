# Evaluation guide — 2.3.0-rc3

Это исправляющий UX release candidate для публикации приложения через HTTPS reverse proxy/HAProxy.

1. Сохраните текущую конфигурацию приложения.
2. Разверните артефакт 2.3.0-rc3.
3. Убедитесь, что HAProxy передаёт `X-Forwarded-Proto: https`, `X-Forwarded-Port: 443` и `X-Forwarded-Host`.
4. В Keycloak для клиента `s3-perf` задайте Valid redirect URI `https://s3-perf.ep-m.tn.tngrp.ru/login/oauth2/code/keycloak` (для диагностики допустимо временно `https://s3-perf.ep-m.tn.tngrp.ru/*`).
5. Откройте `https://s3-perf.ep-m.tn.tngrp.ru/` и выполните вход.
6. Проверьте, что запрос авторизации содержит `redirect_uri=https://s3-perf.ep-m.tn.tngrp.ru/login/oauth2/code/keycloak`, а не HTTP-вариант.
7. Проверьте client roles ADMIN, OPERATOR и VIEWER и кнопку выхода.
8. Выполните smoke-test основных разделов приложения.
