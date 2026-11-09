# Kafka 2.4.0-M1 — Acceptance checklist

M1 establishes the Kafka/KRaft load-testing foundation.

## Functional scope

- Kafka Client 4.0 dependency and Kafka workload type.
- PostgreSQL schema for Kafka connection profiles and producer-test history.
- Kafka profile CRUD with PLAINTEXT, SSL, SASL_PLAINTEXT and SASL_SSL.
- SASL mechanisms: PLAIN, SCRAM-SHA-256, SCRAM-SHA-512.
- SASL password sources: Vault KV v2 and environment variables. Passwords are not stored in PostgreSQL.
- Optional PEM CA certificate path for Kafka TLS.
- Connectivity/metadata check: cluster id, controller id, brokers and topic list.
- Producer load test: message size/count, threads, target msg/s, acks, compression, batch size, linger and idempotence.
- Runtime/history metrics: sent messages/bytes, msg/s, MiB/s, latency avg/p95/p99/max, errors and duration.
- Kafka UI and Kafka Profiles UI integrated with the common UX shell.
- Home dashboard navigation/card for Kafka.
- Distributed agents advertise KAFKA capability and can execute Kafka producer assignments through `/api/distributed-tests/kafka`.

## M1 acceptance tests

1. Create and edit a PLAINTEXT Kafka profile and verify metadata from a KRaft cluster.
2. Create an SSL profile with a PEM CA and verify connectivity.
3. Create a SASL_SSL profile whose password comes from Vault KV v2 and verify that no password is stored in PostgreSQL.
4. Run a local producer test and confirm live Runtime metrics and a persisted history row.
5. Run a TARGET_RATE producer test and confirm approximate rate limiting.
6. Register an agent and confirm `KAFKA` in its capabilities.
7. Submit a distributed Kafka producer run and confirm assignment, progress reporting and terminal state.
8. Confirm ADMIN can manage Kafka profiles, OPERATOR can run tests, and VIEWER cannot start/manage them.
9. Run `mvn clean verify` successfully.

Consumer, E2E consistency, replication/KRaft health and failover remain for M2–M4.
