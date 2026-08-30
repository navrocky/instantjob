# InstantJob

InstantJob — прототип независимого от языка и фреймворка сервиса для постановки и планирования фоновых задач. Клиент описывает задачу через HTTP API, а сервис хранит её состояние и, в перспективе, вызывает настроенный callback.

Проект написан на Kotlin Multiplatform:

- `server` — нативный HTTP-сервис на Ktor/CIO;
- `dashboard` — заготовка браузерного интерфейса на Kotlin/JS.

## Текущее состояние

Проект находится на ранней стадии разработки. OpenAPI 3.2 является источником REST-контракта; `openapi-yagen` генерирует Ktor routes, handler interfaces, DTO и validation. Реализованы PostgreSQL persistence, immediate/at/interval/cron расписания, идемпотентность, pull leases с heartbeat, retry/backoff и HTTP callbacks.

Production runtime использует `PostgresSchedulerRepository`; in-memory `SchedulerStore` оставлен для быстрых unit-тестов. Dashboard содержит только стартовую страницу.

## Требования

- Linux x86_64 или Linux ARM64;
- JDK, совместимый с Gradle 9.3;
- системные заголовки pthread для cinterop;
- интернет при первом запуске Gradle Wrapper и загрузке зависимостей.
- `openapi-yagen` с `builtin:kotlin_ktor_server` для OpenAPI 3.2 в `PATH`;
- `libpq` для линковки PostgreSQL backend.

Windows-цель объявлена в конфигурации, но исходники окружения и pthread cinterop сейчас ориентированы на Linux. macOS-цели отключены.

## Структура проекта

```text
.
├── dashboard/                 # Kotlin/JS dashboard
│   └── src/jsMain/
├── server/                    # Kotlin/Native сервер
│   └── src/
│       ├── commonMain/        # API, доменная логика, конфигурация, логирование
│       ├── commonTest/        # общие тесты
│       ├── linuxMain/         # Linux-реализация чтения environment variables
│       ├── nativeMain/        # native entry point и console logger
│       └── nativeInterop/     # pthread cinterop
├── build.gradle.kts
└── settings.gradle.kts
```

## Сборка и запуск

Все команды выполняются из корня репозитория:

```bash
# запустить PostgreSQL для локальной разработки
docker compose -f env/compose.yaml up -d --wait

# проверить проект
./gradlew check

# запустить PostgreSQL integration tests
INSTANTJOB_TEST_POSTGRES=true ./gradlew :server:linuxX64Test

# собрать debug-бинарник сервера для Linux x86_64
./gradlew :server:linkInstajobDebugExecutableLinuxX64

# собрать development bundle dashboard
./gradlew :dashboard:jsBrowserDevelopmentWebpack
```

Тестовый PostgreSQL доступен на `127.0.0.1:55432`; база, пользователь и пароль — `instantjob`, `instantjob`, `test-password`. Данные сохраняются в Docker volume `instantjob-dev_postgres-data`.

```bash
# остановить окружение, сохранив данные
docker compose -f env/compose.yaml down

# остановить окружение и удалить тестовые данные
docker compose -f env/compose.yaml down --volumes
```

Если установлен [dcw](https://github.com/navrocky/dcw), тестовое окружение можно переключать из корня проекта короче:

```bash
dcw up
dcw down

# остановить окружение и удалить его volumes
dcw down -p
```

Конфигурация читается из переменных окружения:

| Переменная | По умолчанию | Назначение |
|---|---:|---|
| `INSTANTJOB_HOST` | `0.0.0.0` | адрес HTTP-сервера |
| `INSTANTJOB_PORT` | `8080` | порт HTTP-сервера |
| `INSTANTJOB_LOGLEVEL` | `DEBUG` | уровень логирования: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `INSTANTJOB_APIKEYS` | `dev-key:dev` | пары `api-key:service`, разделённые запятыми |
| `INSTANTJOB_CALLBACKSECRETS` | пусто | пары `secretRef=Authorization value` |
| `INSTANTJOB_CALLBACKALLOWHTTP` | `false` | разрешить callback по HTTP вместо HTTPS |
| `INSTANTJOB_DATABASE_HOST` | `127.0.0.1` | адрес PostgreSQL |
| `INSTANTJOB_DATABASE_PORT` | `5432` | порт PostgreSQL |
| `INSTANTJOB_DATABASE_NAME` | `instantjob` | база данных |
| `INSTANTJOB_DATABASE_USER` | `instantjob` | пользователь PostgreSQL |
| `INSTANTJOB_DATABASE_PASSWORD` | — | обязательный пароль PostgreSQL |
| `INSTANTJOB_DATABASE_POOLSIZE` | `10` | размер пула соединений |

## HTTP API

Все ответы содержат поле `code`. Если клиент передаёт `X-Request-Id`, значение используется в контексте запроса; иначе сервис генерирует идентификатор сам.

### Создать задачу

```http
POST /v1/jobs
Content-Type: application/json
```

Пример тела запроса:

```json
{
  "targetService": "report-worker",
  "taskName": "build-report",
  "idempotencyKey": "daily-report-2026-08-29",
  "parameters": { "reportId": 42 },
  "delivery": {
    "type": "HTTP_CALLBACK",
    "method": "POST",
    "url": "https://example.com/callback",
    "parametersType": "JSON"
  },
  "retryPolicy": {
    "maxAttempts": 5,
    "initialDelaySeconds": 5,
    "multiplier": 2.0,
    "maxDelaySeconds": 300,
    "jitter": 0.2
  },
  "schedule": {
    "type": "INTERVAL",
    "everySeconds": 3600
  }
}
```

Успешный ответ:

```json
{ "code": "SUCCESS", "created": true, "job": { "id": "..." } }
```

Повторный идентичный запрос с тем же `idempotencyKey` возвращает исходную задачу с `created: false`; другой payload получает `409`.

### Получить задачу

```http
GET /v1/jobs/{id}
```

### Отменить задачу

```http
DELETE /v1/jobs/{id}
```

Отмена запрещает будущие запуски, но не прерывает execution, уже взятый worker-ом.

Ошибки возвращаются с HTTP-статусом `400`, `404` или `500` и телом следующего вида:

```json
{
  "code": "NOT_FOUND",
  "message": "Job could not be found",
  "requestId": "..."
}
```

## Разработка

Перед коммитом запускайте `./gradlew check`; если менялся конкретный target, дополнительно собирайте его артефакт. PostgreSQL integration tests включаются переменной `INSTANTJOB_TEST_POSTGRES=true` и ожидают тестовую БД `instantjob` на `127.0.0.1:55432` с пользователем `instantjob` и паролем `test-password`.

Правила для автоматизированных участников и краткая карта архитектуры находятся в [AGENTS.md](AGENTS.md).
