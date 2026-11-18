# ЭВО.СНТ S3 2.5.0-rc2

Второй release candidate линии 2.5 заменяет `2.5.0-rc1`, в котором post-release smoke обнаружил
ошибку запуска приложения в режиме Agent. RC предназначен для проверки устойчивой автоматизации и
reference soak в изолированном внутреннем контуре. Использовать его как production-релиз до
завершения эталонного прогона не рекомендуется.

## Исправление RC2

- Coordinator-only сервис идемпотентного Run API теперь создаётся только в режиме `COORDINATOR`.
- Режим `AGENT` больше не требует отсутствующие coordinator-only сервисы ClickHouse и
  распределённых запусков при старте Spring-контекста.
- Добавлен регрессионный тест полного запуска приложения в режиме Agent.
- Post-release smoke теперь немедленно сообщает о завершившемся Agent-контейнере вместо ожидания
  полного timeout регистрации.

## Durable Coordinator

- Реестр Agent, management-state и отпечатки межсервисных токенов сохраняются в PostgreSQL.
- Назначения и прогресс распределённых запусков восстанавливаются после рестарта Coordinator.
- Reconciliation возвращает фактическое состояние Agent и завершает зависшие восстановленные
  прогоны по настраиваемому timeout.
- Единая история объединяет локальные и распределённые S3, ClickHouse и Kafka-тесты, поддерживает
  фильтры и повторный запуск из сохранённой конфигурации.

## Run API и автоматизация

- Добавлен версионированный Run API v1 с контрактом OpenAPI 3.0.3.
- Все команды запуска требуют `Idempotency-Key`; в PostgreSQL сохраняются только SHA-256 отпечатки
  клиента, ключа и нормализованного запроса.
- Стабильные ошибки используют `application/problem+json`, машинный `code` и `X-Request-ID`.
- CLI `evo-snt 0.5.0` поддерживает profiles, start, wait, export, quality gates и soak для S3,
  ClickHouse и Kafka в LOCAL/DISTRIBUTED scope.
- Bearer token может читаться из файла, включая systemd `LoadCredential`, без передачи секрета в
  аргументах процесса.

## Performance gates и reference soak

- Каталог benchmark-сценариев и политики quality gate проверяют throughput, latency, error rate,
  число операций/участников и отклонение от совместимого baseline.
- Reference soak выполняет повторяемые окна, применяет gate к каждому окну и контролирует drift
  operations/s, MiB/s, p95 и суммарного error rate.
- Для сервера без GitHub добавлены hardened systemd service/timer и уникальные idempotency prefixes.
- Полный evidence хранится только во внутреннем контуре. Отдельная обезличенная сводка строится по
  белому списку и не содержит URL, profile/Agent/baseline/run UUID, idempotency key, request ID,
  параметров нагрузки или диагностических сообщений.
- GitHub Actions при подключённом Coordinator публикует только обезличенную сводку, а не полный
  evidence.

## Поставка и проверка

- Release содержит JAR, sources, Javadoc, CycloneDX SBOM, SHA-256 checksums и prerelease Docker
  image `ghcr.io/phibus-dev/s3-performance-test-web:2.5.0-rc2`.
- Пакет `evo-snt-offline-soak-2.5.0-rc2.tar.gz` содержит CLI, локальный runner, systemd units,
  безопасные примеры конфигурации и инструкцию по развёртыванию.
- Browser E2E проверяет общий UI-каркас, навигацию, мониторинг, mobile layout и legacy redirects.
- Post-release smoke проверяет Flyway V27, readiness, метрики, запуск Agent, durable Agent registry
  и восстановление Coordinator.

## Совместимость и миграции

При обновлении с 2.4.0 Flyway применяет V25–V27: durable Agent registry, durable distributed runs
и API idempotency. Переход с rc1 на rc2 не добавляет миграций базы данных. Старые UI/API-маршруты
и формат bootstrap сохраняются. Coordinator и все Agent следует обновлять одновременно; перед
обновлением обязательны backup PostgreSQL и bootstrap-файла.
