# ЭВО.СНТ S3 2.4.0-rc18

Восемнадцатый release candidate версии 2.4.0 посвящён предсказуемому потреблению ресурсов, управляемым дедлайнам и реальным интеграционным проверкам Kafka/ClickHouse.

## Изменения относительно rc17

### Общий admission control

- Локальные и agent-side S3, ClickHouse и Kafka M1–M4 прогоны учитываются в общем лимите `max-concurrent-runs`.
- Высокопараллельные S3 multipart, ClickHouse и Kafka producer операции используют общий bounded executor и лимит `max-worker-tasks` вместо отдельных пулов на каждый прогон.
- При исчерпании ёмкости API возвращает `429 Too Many Requests` и `Retry-After: 5`; причина сохраняется для уже созданной записи истории.
- Глобальный deadline прерывает задачу, переводит прогон в `CANCELLED` и освобождает permit даже для задачи, не успевшей выйти из очереди.
- Добавлены Micrometer-метрики активной/доступной ёмкости, отказов admission control и превышений deadline.

### Agent telemetry

- Heartbeat/registration tags содержат максимальное, активное и доступное число прогонов/workers.
- Агент передаёт используемую и максимальную JVM memory, что позволяет координатору принимать дальнейшие решения о размещении нагрузки.

### PostgreSQL

- Прямые подключения заменены reloadable HikariCP.
- При изменении PostgreSQL URL, пользователя или зашифрованного пароля новый pool создаётся атомарно, а прежний закрывается после переключения.
- Настраиваются maximum/minimum pool size, connection/validation/idle/max-lifetime/leak-detection timeout.
- Для `JdbcTemplate` и Spring transactions введены отдельные query/transaction timeout.
- Экспортируются стандартные `hikaricp_*` метрики; security audit использует общий DataSource.

### Реальные интеграционные тесты

- Kafka Testcontainers проверяет discovery кластера, создание topic и producer-to-consumer round trip.
- ClickHouse Testcontainers вместе с настоящим ClickHouse Keeper проверяет `ReplicatedMergeTree`, запись/чтение и фактический load engine.
- PostgreSQL Testcontainers проверяет горячую замену Hikari pool и регистрацию метрик.
- Добавлены unit-тесты глобальных run/worker quotas, deadline interrupt и освобождения permit для не стартовавшей задачи.

## Совместимость

Новых Flyway-миграций после V24 нет. Форматы REST API и bootstrap-файла не изменены. Новые лимиты имеют консервативные значения по умолчанию и могут быть переопределены environment variables.
