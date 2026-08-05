# S3 Performance Test Web

Java 21 / Spring Boot приложение для функционального и нагрузочного тестирования AWS S3, MinIO и других S3-совместимых хранилищ.

## Возможности

- Web UI и REST API;
- параллельная multipart-загрузка;
- p50/p95/p99 latency;
- JSON/CSV-отчёты;
- автоматическое удаление тестовых объектов;
- bootstrap-режим настройки внешних PostgreSQL, Vault и S3;
- endpoint состояния `/actuator/health`.

## Bootstrap mode

При первом запуске или при недоступном PostgreSQL приложение разрешает только:

```text
/settings
/api/settings/**
/actuator/health
/static/**
```

Остальные страницы перенаправляются в `/settings`. После успешной проверки PostgreSQL приложение переходит в состояние `READY`. Изменение JDBC-настроек применяется после перезапуска.

## Внешний PostgreSQL

В Web UI используется готовая JDBC-строка, например:

```text
jdbc:postgresql://postgres.example.org:5432/s3_performance?sslmode=require
```

PostgreSQL не включён в приложение и не запускается через Docker Compose.

## HashiCorp Vault

Заявлена поддержка **HashiCorp Vault Community Edition 2.0.x**. В версии 1.3 реализуется Token authentication. AppRole планируется в следующем релизе.

Настраиваются:

- Vault address;
- token;
- KV mount;
- secret prefix;
- TLS verification;
- путь к CA certificate;
- путь и имена полей секрета для профиля S3.

## Bootstrap-хранилище

По умолчанию настройки сохраняются в:

```text
config/bootstrap-settings.json
```

Путь можно изменить:

```bash
export S3_PERF_BOOTSTRAP_FILE=/opt/s3-performance/config/bootstrap-settings.json
```

Пароли, Vault token и ручные S3 credentials шифруются AES-GCM. До сохранения секретов задайте мастер-фразу:

```bash
export S3_PERF_BOOTSTRAP_KEY='use-a-long-random-secret-value'
```

Мастер-фраза не записывается в bootstrap-файл.

## Запуск

```bash
mvn clean verify
java -jar target/s3-multipart-uploader-1.3.0-SNAPSHOT.jar
```

Откройте:

```text
http://localhost:8080/settings
```

## Docker

```bash
docker build -t s3-performance-test-web:1.3.0 .
docker run --rm -p 8080:8080 \
  -e S3_PERF_BOOTSTRAP_KEY='use-a-long-random-secret-value' \
  -v "$PWD/config:/app/config" \
  s3-performance-test-web:1.3.0
```

Приложение подключается к внешним PostgreSQL, Vault и S3/MinIO по адресам, указанным в Web UI.
