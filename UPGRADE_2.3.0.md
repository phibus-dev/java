# Upgrade to 2.3.0

1. Сохраните текущую конфигурацию и bootstrap-settings.json.
2. Обновите приложение/контейнер до версии 2.3.0.
3. Для публикации через HAProxy убедитесь, что передаются X-Forwarded-Proto=https, X-Forwarded-Port=443 и X-Forwarded-Host.
4. В Keycloak для клиента приложения укажите Valid redirect URI `https://s3-perf.ep-m.tn.tngrp.ru/login/oauth2/code/keycloak`.
5. В Keycloak укажите Valid post logout redirect URIs `https://s3-perf.ep-m.tn.tngrp.ru/*`.
6. После запуска проверьте `/actuator/health`, вход, выход, титульную страницу, S3 Profiles, S3/ClickHouse tests, Monitoring, Replication, HA Dashboard и Failover.
7. Проверьте UI под ролями ADMIN, OPERATOR и VIEWER.
