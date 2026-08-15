# ЭВО.СНТ Performance 2.2.3-rc1

Первый release candidate ветки 2.2.3 переводит специализированный S3-инструмент на расширяемую платформу нагрузочного тестирования и добавляет полноценный ClickHouse MVP.

## Основные изменения

- сохранение полной совместимости существующих S3-тестов;
- выделение универсального API движков нагрузочного тестирования;
- второй движок нагрузки — ClickHouse;
- ClickHouse profiles с несколькими endpoint, зашифрованным паролем, connection test и topology discovery;
- операции `INSERT`, `SELECT` и `INSERT_SELECT`, режимы по строкам и длительности, warm-up, concurrency и batch size;
- автоматическое создание тестовой MergeTree-таблицы;
- локальное и распределённое выполнение ClickHouse-нагрузок;
- отдельная история ClickHouse, детали, сравнение и тренды;
- наблюдаемость `system.replicas`, replication queue, readonly/inactive replicas и ClickHouse Keeper;
- сценарии `REPLICATED_INSERT`, `REPLICATION_CATCHUP` и `REPLICA_CONSISTENCY`;
- подготовка ReplicatedMergeTree-таблиц и управляемый failover с измерением отказов, восстановления и консистентности;
- сохранение общей инфраструктуры Coordinator/Agent, history, scheduler, trends, reports, Prometheus/Grafana и security.

## База данных

Flyway добавляет миграции:

- V9 — профили ClickHouse;
- V10 — история нагрузочных тестов ClickHouse;
- V11 — snapshots состояния репликации;
- V12 — история replicated-сценариев;
- V13 — история failover-тестов.

## Совместимость

- формат существующих S3-профилей и S3 API сохранён;
- Coordinator и Agents следует обновлять совместно;
- для ClickHouse-функций требуется настроенный PostgreSQL;
- ClickHouse подключается по HTTP(S) через JDBC driver 0.9.8;
- Java 21 остаётся целевой версией runtime.

## Статус

Версия предназначена для проверки ClickHouse MVP и новых HA-сценариев перед стабильным выпуском. S3-функциональность обратно совместима с 2.2.2.
