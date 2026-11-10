# ЭВО.СНТ S3 2.4.0

Стабильный выпуск 2.4.0 сформирован на базе полностью проверенного 2.4.0-rc18. Между rc18 и стабильной версией функциональных изменений и новых миграций базы данных нет.

## Kafka M1–M4

- Добавлены профили Kafka с PLAINTEXT/SSL/SASL, Vault и произвольными Kafka Client properties.
- Реализованы Producer и Consumer load tests, Producer → Consumer E2E, latency, lag и проверки consistency.
- Добавлены KRaft health, replication/ISR, partition scaling, failover/resilience и распределённая Kafka-нагрузка.
- Target rate применяется глобально ко всем worker-потокам; причины ошибок запуска и повторные попытки отправки сохраняются в runtime и истории.

## Корректность S3-метрик

- Для multipart upload операция однозначно соответствует одному `UploadPart`.
- Отдельно измеряются latency отдельных частей, фактических S3 API requests и объектных end-to-end операций.
- Перцентили строятся по HDR-гистограммам; распределённые агенты передают сжатые гистограммы для глобальной агрегации.
- Ошибки SDK учитываются в операциях и latency, а служебные precondition/cleanup запросы исключены из измеряемого результата.
- История сохраняет scope, sample count, workload-параметры и метрики, необходимые для корректного сравнения и повторного запуска.

## ClickHouse и распределённое выполнение

- Исправлен учёт запросов и строк ClickHouse, добавлены profiles/discovery, replication observability, Keeper health, replicated tests и управляемые failover-сценарии.
- Регистрация агентов идемпотентна; межсервисная авторизация использует `X-Agent-Token` с совместимым Bearer fallback.
- Резервирование агентов и возврат capacity выполняются атомарно, identity-файл создаётся с правами `0600`.
- Heartbeat содержит занятую и доступную runtime-ёмкость, а также сведения о JVM memory.

## Управление ресурсами и PostgreSQL

- Общий admission control ограничивает одновременные прогоны и суммарное число S3/ClickHouse/Kafka worker-задач.
- Перегруженный узел отвечает `429 Too Many Requests`; глобальный deadline отменяет прогон и освобождает ресурсы, включая ещё не стартовавшие задачи.
- Неограниченные пулы заменены общими bounded executors, добавлены runtime-метрики и Prometheus alerts.
- PostgreSQL переведён на reloadable HikariCP с атомарной заменой пула, query/transaction timeout и `hikaricp_*` метриками.

## UI, безопасность и поставка

- Все серверные страницы используют общий Thymeleaf-каркас, унифицированные навигацию, таблицы, формы, breadcrumbs и role-aware действия.
- Усилено экранирование динамических значений; пароль экспорта конфигурации передаётся только POST-запросом и не попадает в URL.
- Секреты исключены из расписаний и распределённых payload; временные AWS credentials поддерживают session token.
- Release workflow публикует executable/source/Javadoc JAR, CycloneDX SBOM, SHA-256 checksums и проверенный Docker image.

## Совместимость и миграции

При обновлении с 2.3.0 Flyway последовательно применяет V21–V24. Миграции добавляют настройки Kafka-профилей, расширяют историю S3-тестов и сохраняют новые latency-метрики. Формат bootstrap-файла и REST API v2 остаются совместимыми. Coordinator и все Agent рекомендуется обновлять одновременно.
