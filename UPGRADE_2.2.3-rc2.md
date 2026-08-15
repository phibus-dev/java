# Обновление до ЭВО.СНТ Performance 2.2.3-rc2

Версия 2.2.3-rc2 является исправляющим выпуском для ClickHouse-функций 2.2.3-rc1.

## Обновление с 2.2.3-rc1

1. Сохранить резервную копию JAR, bootstrap-конфигурации и PostgreSQL.
2. Остановить Coordinator и Agents.
3. Заменить JAR на `evo-snt-s3-2.2.3-rc2.jar` либо Docker image на тег `2.2.3-rc2`.
4. Запустить Coordinator и проверить `/actuator/health/readiness`.
5. Запустить Agents и проверить регистрацию/heartbeat.
6. Выполнить короткий ClickHouse INSERT-тест и убедиться, что итог появился в истории.
7. Проверить страницы `/clickhouse/replication`, `/clickhouse/replicated-tests` и `/clickhouse/failover-tests`.

## PostgreSQL

Новых миграций по сравнению с 2.2.3-rc1 нет. Coordinator продолжает использовать таблицы Flyway V9–V13. Ручное изменение схемы не требуется.

## Проверка после обновления

- завершённый ClickHouse-тест присутствует в истории со статусом `COMPLETED` или `FAILED`;
- Replication API возвращает снимок даже при временной ошибке сохранения истории или публикации метрик;
- Replicated* и Failover-сценарии запускаются независимо от очереди обычных нагрузочных тестов;
- инфраструктурные ошибки отображаются в UI понятным сообщением и записываются в журнал сервиса.

## Откат

1. Остановить Coordinator и Agents.
2. Вернуть JAR/Docker image `2.2.3-rc1`.
3. Запустить Coordinator, затем Agents.
4. Повторить readiness и короткий S3/ClickHouse smoke test.

Откат схемы PostgreSQL между rc2 и rc1 не требуется.
