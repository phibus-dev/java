# Kafka 2.4.0-M4 acceptance

## Failover Scenario

1. Настроить Kafka profile и тестовый topic с replication factor >= 3.
2. Запустить `/kafka/m4` с warm-up не менее 10 секунд и `acks=all`.
3. После окончания warm-up вывести один broker из строя внешним способом.
4. Убедиться, что runtime фиксирует хотя бы один из признаков отказа: leader change, controller change, under-replicated partition, offline partition или producer error.
5. Вернуть broker в кластер и дождаться восстановления ISR.
6. Проверить `Время восстановления Kafka`, итоговый статус и сохранение результата в истории.
7. Повторить сценарий для active KRaft controller при наличии отдельного controller quorum.

## Distributed Kafka load

1. Зарегистрировать минимум два агента с capability `KAFKA`.
2. Запустить distributed Kafka producer test через `/api/distributed-tests/kafka`.
3. Открыть `/kafka/m4` и убедиться, что dashboard агрегирует количество агентов, msg/s, MiB/s, p99 и ошибки.
4. Остановить один load agent во время теста и убедиться, что его terminal/error state отражается в distributed runtime.

## Критерии M4

- Failover run всегда сохраняется, включая FAILED и COMPLETED_NOT_RECOVERED.
- При реальном отказе фиксируется момент деградации и, если кластер восстановился, recovery time.
- История содержит controller/leader changes, max under-replicated/offline и producer errors.
- Приложение не требует SSH/systemd/Kubernetes прав на Kafka и не выполняет разрушительные инфраструктурные операции.
- Distributed dashboard отображает агрегированную Kafka-нагрузку нескольких агентов.
- Maven CI, tests, SpotBugs, JAR, SBOM и JaCoCo завершаются успешно.
