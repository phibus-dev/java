# S3 Multipart Uploader

Утилита на Java 21 для генерации потока случайных данных и загрузки объекта в AWS S3 или S3-совместимое хранилище, включая MinIO.

## Возможности

- обычный `PutObject` для объектов меньше 5 MiB;
- Multipart Upload для крупных объектов;
- автоматическая корректировка размера части с учётом ограничения S3 в 10 000 частей;
- расчёт скорости загрузки каждой части и всей операции;
- автоматический `AbortMultipartUpload` при ошибке;
- кастомный endpoint и path-style access;
- сборка Maven, unit-тесты, Docker-образ и GitHub Actions CI.

## Требования

- JDK 21;
- Maven 3.9+;
- доступ к S3-совместимому хранилищу.

## Сборка и тесты

```bash
mvn clean verify
```

Исполняемый fat-jar создаётся в `target/`:

```bash
java -jar target/s3-multipart-uploader-1.0.0-SNAPSHOT.jar
```

Размер объекта также можно передать первым аргументом в байтах:

```bash
java -jar target/s3-multipart-uploader-1.0.0-SNAPSHOT.jar 1073741824
```

## Переменные окружения

| Переменная | Обязательность | Описание |
|---|---:|---|
| `S3_BUCKET` | да | имя бакета |
| `S3_REGION` | да | регион, например `us-east-1` |
| `S3_ENDPOINT` | нет | endpoint MinIO или другого S3-совместимого сервиса |
| `S3_ACCESS_KEY` | нет | access key; без него используется стандартная цепочка AWS SDK |
| `S3_SECRET_KEY` | нет | secret key |
| `S3_SIZE_BYTES` | нет | размер объекта; по умолчанию 1 GiB |
| `S3_PART_SIZE_BYTES` | нет | размер multipart-части; по умолчанию 64 MiB |
| `S3_OBJECT_KEY` | нет | ключ объекта; по умолчанию формируется автоматически |
| `S3_PATH_STYLE` | нет | path-style access; по умолчанию `true` |

Пример для MinIO:

```bash
export S3_BUCKET=test
export S3_REGION=us-east-1
export S3_ENDPOINT=http://minio.example.org:9000
export S3_ACCESS_KEY=minioadmin
export S3_SECRET_KEY=minioadmin
export S3_SIZE_BYTES=1073741824

mvn clean package
java -jar target/s3-multipart-uploader-1.0.0-SNAPSHOT.jar
```

## Docker

```bash
docker build -t s3-multipart-uploader .

docker run --rm \
  -e S3_BUCKET=test \
  -e S3_REGION=us-east-1 \
  -e S3_ENDPOINT=http://minio.example.org:9000 \
  -e S3_ACCESS_KEY=minioadmin \
  -e S3_SECRET_KEY=minioadmin \
  -e S3_SIZE_BYTES=1073741824 \
  s3-multipart-uploader
```

## Структура

```text
src/main/java/dev/phibus/s3/S3MultipartUploader.java
src/test/java/dev/phibus/s3/S3MultipartUploaderTest.java
pom.xml
Dockerfile
.github/workflows/maven.yml
```

> Утилита генерирует неповторяемые случайные данные и предназначена прежде всего для функционального и нагрузочного тестирования S3/MinIO.
