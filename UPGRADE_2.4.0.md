# Upgrade to 2.4.0

## Подготовка

1. Остановите запуск новых заданий и дождитесь завершения активных S3, ClickHouse и Kafka-тестов.
2. Сохраните bootstrap-файл, зашифрованные настройки и резервную копию PostgreSQL.
3. Зафиксируйте текущие версии Coordinator/Agent и значения environment variables для возможного отката.
4. Проверьте свободное место для резервной копии и журналов Flyway.

## Обновление

1. Обновите Coordinator до `2.4.0` и запустите его с прежним `S3_PERF_BOOTSTRAP_KEY`.
2. При обновлении с 2.3.0 дождитесь применения Flyway V21–V24. При переходе с rc17/rc18 новых миграций нет.
3. Убедитесь, что `/actuator/health/readiness` возвращает `UP`, а `/actuator/prometheus` содержит `s3perf_runtime_*` и `hikaricp_*`.
4. Обновите все Agent до `2.4.0`. Одновременная версия Coordinator и Agent необходима для HDR-агрегации и полной telemetry runtime capacity.
5. Импортируйте обновлённые Grafana dashboards и Prometheus alerts.

## Важные изменения

- Межсервисная авторизация Agent использует `X-Agent-Token`; совместимый Bearer fallback сохранён.
- `GET /api/config/export` отключён. Экспорт с паролем выполняется через `POST application/x-www-form-urlencoded`.
- Ручные S3 credentials больше не передаются агентам и не сохраняются в расписаниях; используйте локальный профиль, environment или Vault.
- При обновлении с 2.3.0 применяются миграции V21–V24. Перед обновлением резервная копия PostgreSQL обязательна.

## Новые настройки ресурсов

| Переменная | По умолчанию | Назначение |
|---|---:|---|
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS` | `8` | Одновременные локальные и agent-side прогоны |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS` | `64` | Суммарное число worker-задач |
| `S3PERF_RUNTIME_MAX_RUN_DURATION_SECONDS` | `86400` | Жёсткий дедлайн прогона |
| `S3PERF_RUNTIME_EXECUTOR_CORE_SIZE` | `2` | Core size executor верхнего уровня |
| `S3PERF_RUNTIME_EXECUTOR_MAX_SIZE` | `8` | Maximum size executor верхнего уровня |
| `S3PERF_RUNTIME_EXECUTOR_QUEUE_CAPACITY` | `32` | Очередь executor верхнего уровня |
| `S3PERF_POSTGRESQL_POOL_MAXIMUM_SIZE` | `16` | Максимальный размер HikariCP |
| `S3PERF_POSTGRESQL_POOL_MINIMUM_IDLE` | `1` | Минимальное число idle connections |
| `S3PERF_POSTGRESQL_CONNECTION_TIMEOUT_MS` | `10000` | Ожидание JDBC connection |
| `S3PERF_POSTGRESQL_QUERY_TIMEOUT_SECONDS` | `30` | Таймаут JdbcTemplate query |
| `S3PERF_POSTGRESQL_TRANSACTION_TIMEOUT_SECONDS` | `60` | Таймаут Spring transaction |

## Проверка после обновления

Изолированную проверку опубликованного контейнера можно запустить workflow
`Post-release smoke`. Для неизменяемой проверки передайте версию `2.4.0` и digest образа из
release workflow. Проверка разворачивает временный PostgreSQL, Coordinator и Agent, проверяет
Flyway V24, метрики, `X-Agent-Token`, восстановление после перезапуска и heartbeat soak.

1. Выполните локальный и распределённый S3 multipart test; проверьте scope, sample count, API и object latency.
2. Запустите Kafka Producer, Consumer/E2E, replication и failover smoke tests.
3. Проверьте ClickHouse load test, replication/Keeper dashboard и доступность профилей.
4. Запустите параллельно S3, ClickHouse и Kafka и убедитесь, что runtime capacity не превышает установленные лимиты.
5. Проверьте HTTP 429 при исчерпании run capacity и `CANCELLED` при превышении deadline в тестовом окружении.
6. Проверьте Hikari metrics и горячую смену PostgreSQL connection settings.
7. Проверьте роли `VIEWER`, `OPERATOR`, `ADMIN`, экспорт `.evos3`, историю и повторный запуск.

## Откат

При откате с 2.4.0 на rc17/rc18 достаточно вернуть Coordinator и все Agent к одной выбранной версии: схема V24 совпадает. Для возврата на 2.3.0 остановите приложение и восстановите одновременно бинарники, bootstrap-файл и резервную копию PostgreSQL, созданную до применения V21–V24.
