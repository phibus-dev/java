# S3 Performance Test Web

Java 21-приложение для функционального и скоростного тестирования AWS S3 и S3-совместимых хранилищ, включая MinIO.

## Web UI MVP

Web-интерфейс позволяет:

- задавать endpoint, region, access key и secret key;
- получать список доступных бакетов;
- выбирать bucket, object key, размер объекта и multipart-части;
- запускать тест асинхронно;
- наблюдать прогресс через Server-Sent Events;
- видеть переданный объём, текущую и среднюю скорость;
- отменять тест;
- просматривать историю запусков в памяти приложения.

Ключи используются только для текущего запуска и не возвращаются в REST-ответах. В MVP они хранятся в памяти процесса до перезапуска; для промышленной эксплуатации рекомендуется HashiCorp Vault.

## Запуск

Требуются JDK 21 и Maven 3.9+.

```bash
mvn clean verify
java -jar target/s3-multipart-uploader-1.1.0-SNAPSHOT.jar
```

Откройте `http://localhost:8080`.

## REST API

```text
POST /api/tests
GET  /api/tests
GET  /api/tests/{id}
POST /api/tests/{id}/cancel
GET  /api/tests/{id}/events
POST /api/buckets
```

Пример запроса:

```json
{
  "endpoint": "http://localhost:9000",
  "bucket": "performance-test",
  "region": "us-east-1",
  "accessKey": "minioadmin",
  "secretKey": "minioadmin",
  "pathStyleAccess": true,
  "objectKey": "performance-test/random-upload.bin",
  "objectSizeMiB": 1024,
  "partSizeMiB": 64,
  "operation": "UPLOAD"
}
```

## Docker

```bash
docker build -t s3-performance-test-web .
docker run --rm -p 8080:8080 s3-performance-test-web
```

## Ограничения MVP

- только последовательный upload-тест;
- история не сохраняется после перезапуска;
- профили endpoint не сохраняются;
- нет Vault, PostgreSQL, download-тестов и экспорта отчётов;
- отмена применяется между multipart-частями.

## Следующие этапы

- профили подключений и HashiCorp Vault;
- параллельный multipart upload через `S3AsyncClient`;
- PostgreSQL для истории;
- графики latency и percentile-метрики;
- download, list, head и delete тесты;
- JSON/CSV-отчёты и Prometheus-метрики.
