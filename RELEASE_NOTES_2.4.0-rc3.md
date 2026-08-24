# ЭВО.СНТ S3 2.4.0-rc3

Третий release candidate версии 2.4.0 для тестирования Kafka-нагрузки.

## Изменения относительно rc2
- исправлена логика Kafka Profiles после сохранения: созданный/обновлённый профиль остаётся выбранным в форме;
- кнопка «Проверить подключение» теперь сразу работает после сохранения профиля и использует его фактический ID;
- для существующего профиля проверка выполняется после выбора через «Изменить»;
- сохранены предыдущие исправления Kafka UI, CSRF, credentials PROFILE и обратной совместимости M1-M4;
- Dockerfile больше не привязан к конкретному номеру RC и автоматически использует собранный executable JAR.

## В составе 2.4.0
- Kafka Producer Load Test;
- Consumer Load Test и consumer lag;
- Producer→Consumer E2E и consistency;
- KRaft Health и Replication Test;
- Partition Scaling;
- Failover Scenario и оценка времени восстановления;
- distributed Kafka load и агрегирование показателей агентов;
- Kafka Profiles с NONE / PROFILE / VAULT / ENVIRONMENT credentials.

## Тестирование
Acceptance checklist: `KAFKA_M3_ACCEPTANCE.md` и `KAFKA_M4_ACCEPTANCE.md`.
