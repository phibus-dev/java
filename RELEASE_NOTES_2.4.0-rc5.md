# ЭВО.СНТ S3 2.4.0-rc5

Пятый release candidate версии 2.4.0.

## Исправления относительно rc4
- исправлены права на проверку подключения Kafka Profile;
- endpoint `/api/kafka/profiles/*/check` теперь доступен ролям `ADMIN` и `OPERATOR`;
- создание, изменение, удаление и назначение Kafka-профилей остаются административными операциями;
- сохранена зависимая форма credentials: `PLAIN`, `ENVIRONMENT`, `VAULT`;
- сохранены исправления CSRF, зашифрованного пароля профиля и проверки подключения из предыдущих RC.

## Kafka 2.4.0
- Producer Load Test;
- Consumer Load Test и E2E;
- KRaft Health и Replication;
- Partition Scaling;
- Failover & Resilience;
- distributed Kafka load testing.
