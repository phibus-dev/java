# ЭВО.СНТ S3 2.4.0-rc6

Шестой release candidate версии 2.4.0.

## Исправления относительно rc5
- исправлен асинхронный расчёт производительности Kafka Producer Load Test;
- итоговые счётчики сообщений и байтов теперь формируются после завершения асинхронных отправок;
- throughput больше не рассчитывается по преждевременно прочитанным значениям счётчиков;
- сохранены исправления CSRF для Kafka Producer и сценариев M2–M4;
- сохранены права ролей `ADMIN` и `OPERATOR` на проверку подключения Kafka Profile.

## Kafka 2.4.0
- Producer Load Test;
- Consumer Load Test и E2E;
- KRaft Health и Replication;
- Partition Scaling;
- Failover & Resilience;
- distributed Kafka load testing.
