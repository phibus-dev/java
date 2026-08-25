# Upgrade / test guide — 2.4.0-rc8

1. Разверните JAR или Docker image 2.4.0-rc8 поверх rc7.
2. Миграций БД относительно rc7 нет.
3. Запустите Producer Load Test и контролируйте Runtime до завершения.
4. Убедитесь, что GET /api/kafka/producer-tests/{id} не возвращает HTTP 500 при одновременной записи latency.
5. Проверьте итоговые p95/p99 и сохранение результата Producer.
6. Запустите Consumer Load Test.
7. Запустите Producer → Consumer E2E.
8. При статусе FAILED убедитесь, что точная причина показана под Runtime и в истории.
9. При ошибке Kafka проверьте ACL READ, DESCRIBE и GROUP по отображённому errorMessage.
