# ЭВО.СНТ S3 2.3.0

Стабильный выпуск 2.3.0 сформирован на базе протестированного 2.3.0-rc6.

## Основные изменения

- новая титульная страница с навигацией и обзором сервиса;
- единый корпоративный UI для основных разделов приложения;
- favicon и web manifest;
- breadcrumbs и глобальный поиск по истории;
- пользовательское меню с логином, client role и версией приложения;
- полноценный OIDC logout через Keycloak;
- role-aware UI для ADMIN / OPERATOR / VIEWER;
- улучшенные карточки состояния сервисов и мониторинга;
- обновлённый UI страницы профилей S3;
- исправлена работа приложения за HTTPS reverse proxy/HAProxy;
- Spring Boot учитывает X-Forwarded-Proto/Host/Port при OAuth2 redirect URI;
- исправлены конфликты маршрута `/` и старт servlet web application;
- сохранены улучшения истории ClickHouse, Replicated tests, Failover и HA Dashboard из линии 2.2.3.

## Keycloak

Для публикации приложения через HTTPS используйте внешний callback:
`https://s3-perf.ep-m.tn.tngrp.ru/login/oauth2/code/keycloak`.

Для OIDC logout разрешите post logout redirect:
`https://s3-perf.ep-m.tn.tngrp.ru/*`.

Версия 2.3.0 является стабильным выпуском после подтверждения работоспособности 2.3.0-rc6.
