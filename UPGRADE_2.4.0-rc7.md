# Upgrade / test guide — 2.4.0-rc7

1. Разверните JAR или Docker image 2.4.0-rc7 поверх rc6.
2. Миграций БД относительно rc6 нет.
3. Выполните Producer Load Test с лимитом in-flight 10 000.
4. Убедитесь, что Runtime обновляется, а итоговые sent messages и throughput фиксируются после завершения теста.
5. Повторите Producer Load Test с несколькими потоками.
6. Запустите Consumer Load Test и убедитесь, что endpoint /api/kafka/consumer-tests возвращает HTTP 202.
7. Запустите Producer → Consumer E2E и убедитесь, что endpoint /api/kafka/e2e-tests возвращает HTTP 202.
8. Дождитесь завершения обоих M2-тестов и проверьте сохранение результатов в истории.
9. Проверьте p95/p99 Producer и отсутствие ошибок PostgreSQL TIMESTAMPTZ в журнале приложения.
