# ЭВО.СНТ S3 2.4.0-rc1

Первый release candidate версии 2.4.0 для тестирования Kafka-нагрузки.

## Kafka M1
- Kafka Profiles и проверка подключения;
- PLAINTEXT/SSL/SASL_PLAINTEXT/SASL_SSL;
- PLAIN/SCRAM-SHA-256/SCRAM-SHA-512;
- получение SASL password из Vault KV v2 или environment;
- Producer Load Test с MAX/TARGET rate;
- runtime/history producer-тестов;
- capability KAFKA для distributed agents.

## Kafka M2
- Consumer Load Test;
- consumer lag и max partition lag;
- Producer→Consumer E2E test;
- consistency: missing, duplicates, out-of-order, corrupted;
- E2E latency avg/p50/p95/p99/max;
- расширенные Runtime/History.

## Kafka M3
- KRaft Health: clusterId, controller, brokers;
- Replication Test: partitions, replicas, ISR, under-replicated/offline partitions, min.insync.replicas violations;
- Partition Scaling на временных topics с измерением msg/s и MiB/s.

## Kafka M4
- Failover Scenario под producer-нагрузкой;
- фиксация деградации topology/ISR;
- controller/leader changes;
- producer errors и throughput degradation;
- метрика «Время восстановления Kafka»;
- операторское внешнее внесение отказа без SSH/systemd/Kubernetes-прав приложению;
- dashboard распределённой Kafka-нагрузки с агрегированием агентов.

## Тестирование
Основные acceptance checklist находятся в KAFKA_M3_ACCEPTANCE.md и KAFKA_M4_ACCEPTANCE.md.
