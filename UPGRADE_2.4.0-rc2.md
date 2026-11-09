# Upgrade / test guide — 2.4.0-rc2

1. Сделайте резервную копию PostgreSQL конфигурационной БД приложения.
2. Разверните JAR или Docker image 2.4.0-rc2.
3. При обновлении с rc1 должна примениться миграция V20; при чистой установке — Kafka migrations V16-V20.
4. Убедитесь, что Kafka-раздел использует общую двухуровневую навигацию приложения.
5. Создайте Kafka Profile и проверьте операции «Сохранить» и «Проверить подключение» — HTTP 403 быть не должно.
6. Для SASL можно использовать `VAULT`, `ENVIRONMENT` или `PROFILE`. Для `PROFILE` задайте `S3_PERF_BOOTSTRAP_KEY`; пароль сохраняется только в зашифрованном виде.
7. Проверьте Producer test и runtime/history.
8. Проверьте Consumer и E2E test, включая consistency и p95/p99 latency.
9. Проверьте KRaft Health и Replication Test.
10. Partition Scaling выполняйте только на тестовом Kafka-кластере/namespace.
11. Для Failover Scenario после warm-up внешним способом остановите выбранный broker/controller и проверьте время восстановления, controller/leader changes, producer errors и URP/offline partitions.
12. Для distributed Kafka test зарегистрируйте несколько агентов с capability KAFKA и проверьте агрегирование msg/s, MiB/s, p99 и errors.

Подробные критерии: `KAFKA_M3_ACCEPTANCE.md` и `KAFKA_M4_ACCEPTANCE.md`.
