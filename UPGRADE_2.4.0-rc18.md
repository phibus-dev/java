# Upgrade / test guide — 2.4.0-rc18

1. Остановите создание новых тестов и дождитесь завершения активных прогонов.
2. Сохраните bootstrap-файл и резервную копию PostgreSQL.
3. Обновите Coordinator и все Agent до `2.4.0-rc18`, затем перезапустите процессы. Новых Flyway-миграций после V24 нет.
4. Для начала оставьте значения по умолчанию: 8 одновременных прогонов, 64 worker-задачи и deadline 86400 секунд.
5. Проверьте `/actuator/prometheus`: должны присутствовать `s3perf_runtime_runs_active`, `s3perf_runtime_workers_active`, доступная ёмкость, rejections/deadlines и `hikaricp_*`.
6. Запустите S3 multipart, ClickHouse и Kafka producer параллельно. Суммарная concurrency не должна превышать `S3PERF_RUNTIME_MAX_WORKER_TASKS`.
7. В тестовом окружении задайте `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS=1` и попробуйте запустить второй прогон. Ожидается HTTP 429 с `Retry-After: 5` без зависшей записи `RUNNING`.
8. В тестовом окружении установите малый `S3PERF_RUNTIME_MAX_RUN_DURATION_SECONDS`, запустите длительный прогон и проверьте статус `CANCELLED` с причиной `Runtime deadline exceeded`.
9. Измените PostgreSQL connection settings через `/settings`. Следующий JDBC-запрос должен использовать новый Hikari pool; если целевая БД ещё не мигрирована, перезапустите Coordinator для выполнения Flyway.
10. Импортируйте обновлённый runtime dashboard и Prometheus alerts; проверьте отображение runtime capacity и Hikari connections.
11. Перед выпуском убедитесь, что Maven CI выполнил Kafka, ClickHouse+Keeper и PostgreSQL Testcontainers, а CodeQL и Dependency Review завершились успешно.

## Новые environment variables

| Переменная | По умолчанию |
|---|---:|
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS` | `8` |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS` | `64` |
| `S3PERF_RUNTIME_MAX_RUN_DURATION_SECONDS` | `86400` |
| `S3PERF_RUNTIME_EXECUTOR_CORE_SIZE` | `2` |
| `S3PERF_RUNTIME_EXECUTOR_MAX_SIZE` | `8` |
| `S3PERF_RUNTIME_EXECUTOR_QUEUE_CAPACITY` | `32` |
| `S3PERF_POSTGRESQL_POOL_MAXIMUM_SIZE` | `16` |
| `S3PERF_POSTGRESQL_POOL_MINIMUM_IDLE` | `1` |
| `S3PERF_POSTGRESQL_CONNECTION_TIMEOUT_MS` | `10000` |
| `S3PERF_POSTGRESQL_VALIDATION_TIMEOUT_MS` | `3000` |
| `S3PERF_POSTGRESQL_IDLE_TIMEOUT_MS` | `600000` |
| `S3PERF_POSTGRESQL_MAX_LIFETIME_MS` | `1800000` |
| `S3PERF_POSTGRESQL_LEAK_DETECTION_MS` | `0` |
| `S3PERF_POSTGRESQL_QUERY_TIMEOUT_SECONDS` | `30` |
| `S3PERF_POSTGRESQL_TRANSACTION_TIMEOUT_SECONDS` | `60` |

## Откат

Остановите Coordinator/Agent и верните JAR или image `2.4.0-rc17`. Схема базы данных в rc18 не менялась, поэтому откат Flyway не требуется. Новые environment variables rc17 игнорирует; их можно оставить или удалить после отката.
