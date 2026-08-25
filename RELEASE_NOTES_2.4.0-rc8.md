# ЭВО.СНТ S3 2.4.0-rc8

Восьмой release candidate версии 2.4.0.

## Исправления относительно rc7

### Kafka Producer Runtime
- сбор latency переведён на рекомендованную схему HDR Histogram Recorder + стабильный накопительный Histogram;
- устранено конкурентное чтение метрик, вызывавшее HTTP 500 при GET /api/kafka/producer-tests/{id};
- interval histogram считывается только при формировании синхронизированного Runtime snapshot;
- диапазон latency ограничен одним часом;
- опрос Runtime выдерживает до пяти последовательных временных ошибок и не прекращается после единичного сбоя.

### Kafka Consumer и E2E
- точный errorMessage отображается под Consumer Runtime;
- точный errorMessage отображается под E2E Runtime / Consistency;
- причина FAILED сохраняется и выводится в истории Consumer / E2E;
- сохранены исправления PostgreSQL TIMESTAMPTZ для запуска и завершения M2-тестов.

## Kafka 2.4.0
- Producer Load Test;
- Consumer Load Test и Producer → Consumer E2E;
- KRaft Health и Replication;
- Partition Scaling;
- Failover & Resilience;
- distributed Kafka load testing.
