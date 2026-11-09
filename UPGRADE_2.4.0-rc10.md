# Upgrade / test guide — 2.4.0-rc10

1. Разверните JAR или Docker image 2.4.0-rc10 поверх rc9.
2. Новых миграций БД относительно rc9 нет.
3. Запустите E2E-тест с 10 000 сообщений, размером 1024 байта и `Target msg/s = 1000`.
4. Повторите тест для 2 000 и 10 000 msg/s.
5. Запустите тест с `Target msg/s = 0`, чтобы определить максимальную доступную скорость текущей конфигурации.
6. Сравните фактические `Producer msg/s` и `Consumer msg/s`.
7. Контролируйте p50, p95, p99, max, Missing, Duplicates, Out-of-order и Corrupted.
8. Успешный тест должен завершиться с `Consistency = PASS`, `Missing = 0` и статусом `COMPLETED`.
9. При `FAILED` используйте точное диагностическое сообщение под Runtime и в истории.

Для сопоставимых результатов сохраняйте одинаковыми topic, количество partitions, replication factor, `acks`, compression, размер сообщения и параметры Kafka-профиля.
