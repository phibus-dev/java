# Evaluation guide — 2.3.0-rc6

1. Разверните 2.3.0-rc6 в тестовой среде.
2. Проверьте вход через Keycloak.
3. В Keycloak для клиента `s3-perf` разрешите `Valid post logout redirect URIs`: `https://s3-perf.ep-m.tn.tngrp.ru/*`.
4. Нажмите «Выйти» и убедитесь, что локальная сессия завершается, выполняется OIDC logout в Keycloak и браузер возвращается на внешнюю HTTPS-страницу приложения.
5. Убедитесь, что повторный вход требует новой/актуальной Keycloak-сессии.
6. Выполните smoke-test главной страницы и страницы `/settings/s3-profiles`.
