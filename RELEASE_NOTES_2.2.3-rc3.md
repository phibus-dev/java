# ЭВО.СНТ Performance 2.2.3-rc3

Третий release candidate исправляет управление ClickHouse-профилями, сохранение временных полей PostgreSQL и повторное создание ReplicatedMergeTree.

## Исправления

- DELETE профиля возвращает `204 No Content` и больше не вызывает ошибку разбора пустого JSON;
- сохранённые ClickHouse-профили можно загрузить в форму, отредактировать и сохранить через `PUT`;
- пустой пароль при редактировании сохраняет действующий пароль;
- временные поля ClickHouse history, replication snapshots, Replicated* и Failover workflow передаются PostgreSQL с явным JDBC типом `TIMESTAMP_WITH_TIMEZONE`;
- устранена ошибка `Can't infer the SQL type to use for an instance of java.time.Instant`;
- `DROP TABLE IF EXISTS ... SYNC` ожидает очистки регистрации реплики перед повторным созданием ReplicatedMergeTree;
- ошибки управления профилями отображаются в Web UI без системного popup.

## Проверки

- Maven `clean verify`;
- CodeQL;
- Dependency Review;
- API/UI regression-тесты управления профилями;
- тесты JDBC timestamp binding и ReplicatedMergeTree DDL.

## Совместимость

- новых миграций PostgreSQL нет;
- конфигурация и данные 2.2.3-rc2 совместимы без преобразования;
- Coordinator и Agents рекомендуется обновлять совместно;
- Java 21 остаётся целевой версией runtime.
