# ЭВО.СНТ S3 2.4.0-rc2

Второй release candidate версии 2.4.0 для тестирования Kafka-нагрузки.

## Изменения относительно rc1
- исправлена двухуровневая навигация Kafka и выравнивание UI со страницами ClickHouse;
- исправлен CSRF для операций Kafka Profiles: сохранение, проверка подключения, назначение профиля по умолчанию и удаление;
- добавлен опциональный источник credentials `PROFILE` с вводом пароля пользователя Kafka;
- пароль Kafka в `PROFILE` хранится только в зашифрованном виде AES-GCM с `S3_PERF_BOOTSTRAP_KEY` и не возвращается через REST API;
- сохранена поддержка `VAULT`, `ENVIRONMENT` и `NONE`;
- добавлена миграция V20 для `password_encrypted`;
- восстановлена обратная совместимость `KafkaProfileService.Profile` и `ProfileRequest` для M1-M4 и существующих тестов.

## Kafka 2.4.0
- Producer Load Test;
- Consumer Load Test и consumer lag;
- Producer→Consumer E2E и consistency;
- KRaft Health и Replication Test;
- Partition Scaling;
- Failover Scenario с оценкой времени восстановления;
- distributed Kafka load и агрегирование показателей агентов.

## Тестирование
Acceptance checklist: `KAFKA_M3_ACCEPTANCE.md` и `KAFKA_M4_ACCEPTANCE.md`.
