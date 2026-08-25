# ЭВО.СНТ S3 2.4.0-rc7

Седьмой release candidate версии 2.4.0.

## Исправления относительно первоначального rc6

### Kafka Producer
- добавлен HDR Histogram для расчёта latency без хранения и полной сортировки выборки;
- Runtime snapshot кэшируется на 250 мс;
- один потокобезопасный KafkaProducer используется всеми потоками теста;
- добавлен управляемый лимит in-flight сообщений через Semaphore;
- лимит in-flight доступен в UI, значение по умолчанию — 10 000;
- итоговый результат фиксируется после завершения callback и producer.flush().

### Kafka Consumer и E2E
- устранён HTTP 500 при запуске Consumer Load Test;
- устранён HTTP 500 при запуске Producer → Consumer E2E;
- started_at заполняется PostgreSQL через DEFAULT CURRENT_TIMESTAMP;
- finished_at передаётся как OffsetDateTime UTC.

## Kafka 2.4.0
- Producer Load Test;
- Consumer Load Test и Producer → Consumer E2E;
- KRaft Health и Replication;
- Partition Scaling;
- Failover & Resilience;
- distributed Kafka load testing.
