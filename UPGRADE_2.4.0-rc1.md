# Upgrade / test guide — 2.4.0-rc1

1. Сделайте резервную копию PostgreSQL конфигурационной БД приложения.
2. Разверните JAR или Docker image 2.4.0-rc1.
3. При старте должны примениться Kafka migrations V16-V19.
4. Создайте Kafka Profile и выполните «Проверить подключение».
5. Для SASL используйте Vault KV v2 либо environment source; пароль не должен сохраняться в PostgreSQL.
6. Проверьте Producer test и отображение runtime/history.
7. Проверьте Consumer и E2E test, включая consistency и p95/p99 latency.
8. Проверьте KRaft Health и Replication Test на тестовом topic.
9. Partition Scaling выполнять только на тестовом Kafka-кластере/namespace: приложение создаёт и удаляет временные topics.
10. Для Failover Scenario запустите тест, дождитесь warm-up, затем внешним способом остановите выбранный broker/controller. Приложение само инфраструктуру Kafka не останавливает.
11. После восстановления проверьте «Время восстановления Kafka», controller/leader changes, producer errors, max under-replicated/offline partitions.
12. Для distributed Kafka test зарегистрируйте несколько агентов с capability KAFKA и проверьте агрегацию msg/s, MiB/s, p99 и errors.

Подробные критерии: KAFKA_M3_ACCEPTANCE.md и KAFKA_M4_ACCEPTANCE.md.
