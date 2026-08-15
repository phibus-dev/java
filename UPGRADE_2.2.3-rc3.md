# Обновление до ЭВО.СНТ Performance 2.2.3-rc3

Версия 2.2.3-rc3 является исправляющим выпуском для ClickHouse-функций 2.2.3-rc2.

## Обновление

1. Сохранить резервную копию JAR, bootstrap-конфигурации и PostgreSQL.
2. Остановить Coordinator и Agents.
3. Заменить JAR на `evo-snt-s3-2.2.3-rc3.jar` либо Docker image на тег `2.2.3-rc3`.
4. Запустить Coordinator и проверить `/actuator/health/readiness`.
5. Запустить Agents и проверить регистрацию/heartbeat.

## Проверка после обновления

1. Создать временный ClickHouse-профиль, изменить его и удалить.
2. Выполнить короткий ClickHouse INSERT-тест и проверить сохранение результата в истории.
3. Запустить Replicated test и убедиться, что отсутствует ошибка JDBC для `Instant`.
4. При подготовке тестовой ReplicatedMergeTree-таблицы использовать уникальный Keeper path и уникальные macros `replica` на ClickHouse nodes.
5. Для повторного создания включить `DROP существующей таблицы перед созданием`.

## PostgreSQL

Новых миграций нет. Используются существующие таблицы Flyway V9–V13. Ручное изменение схемы не требуется.

## Откат

1. Остановить Coordinator и Agents.
2. Вернуть JAR/Docker image `2.2.3-rc2`.
3. Запустить Coordinator, затем Agents.
4. Повторить readiness и короткий S3/ClickHouse smoke test.

Откат схемы PostgreSQL между rc3 и rc2 не требуется.
