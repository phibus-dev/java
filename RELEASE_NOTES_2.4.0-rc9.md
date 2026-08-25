# ЭВО.СНТ S3 2.4.0-rc9

Девятый release candidate версии 2.4.0.

## Изменения относительно rc8

### Kafka-профили
- добавлено поле `session.timeout.ms` со значением по умолчанию 10 000 мс;
- значение сохраняется в профиле и применяется в Consumer Load Test и Producer → Consumer E2E;
- существующие Kafka-профили автоматически получают значение 10 000 мс;
- добавлено управление произвольными параметрами Kafka Client в виде пар «параметр — значение»;
- дополнительные параметры применяются к Admin Client, Producer и Consumer и синхронизируются на распределённые агенты;
- параметры подключения, безопасности, `client.id` и `session.timeout.ms` защищены от дублирования через произвольные настройки;
- добавлена проверка пустых и повторяющихся ключей параметров.

### Миграции БД
- `V21__kafka_profile_session_timeout.sql` — хранение `session.timeout.ms`;
- `V22__kafka_profile_custom_properties.sql` — хранение дополнительных параметров Kafka Client.

## Kafka 2.4.0
- Producer Load Test;
- Consumer Load Test и Producer → Consumer E2E;
- KRaft Health и Replication;
- Partition Scaling;
- Failover & Resilience;
- distributed Kafka load testing.
