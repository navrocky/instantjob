# InstantJob

InstantJob is a language- and framework-independent service for reliable background job scheduling. Other services register jobs through the REST API, and InstantJob runs them through HTTP callbacks or leases them to pull workers using the lease/heartbeat protocol.

The server is written in Kotlin Multiplatform and builds as a native Linux binary using Ktor/CIO. PostgreSQL is the source of truth, and delivery follows at-least-once semantics.

## Features

- immediate, scheduled, interval-based, and six-field cron execution with IANA time zones;
- one-time, bounded, and indefinitely recurring jobs;
- HTTP callbacks and pull workers;
- transactional job-submission idempotency;
- leases, heartbeats, recovery of abandoned jobs, and retries with exponential backoff;
- concurrent operation of multiple instances through PostgreSQL `SKIP LOCKED`;
- an OpenAPI 3.2 contract-first API with Ktor route and DTO generation via `openapi-yagen`;
- liveness and readiness endpoints.

The dashboard is currently a placeholder. Retention, Prometheus metrics, full ACL support, and production hardening of callback SSRF protection are not yet complete.

## Implementation map

The features listed above are implemented in the following components:

| Feature | Implementation |
|---|---|
| immediate, AT, interval, and cron schedules | [`ScheduleCalculator.kt`](server/src/commonMain/kotlin/scheduler/ScheduleCalculator.kt) |
| PostgreSQL persistence, transactional idempotency, leases, retries, and `SKIP LOCKED` coordination | [`PostgresSchedulerRepository.kt`](server/src/commonMain/kotlin/persistence/PostgresSchedulerRepository.kt) |
| HTTP callback delivery and callback authentication | [`CallbackDispatcher.kt`](server/src/commonMain/kotlin/scheduler/CallbackDispatcher.kt) |
| REST handlers and API-key service identity | [`ApiHandlers.kt`](server/src/commonMain/kotlin/api/ApiHandlers.kt) and [`AuthRegistry.kt`](server/src/commonMain/kotlin/auth/AuthRegistry.kt) |
| generated routes and DTOs | [`api/openapi.yaml`](api/openapi.yaml) and the `generateOpenApiServer` Gradle task |
| database schema | [`V001__initial_schema.sql`](server/src/commonMain/resources/db/migration/V001__initial_schema.sql) |

Cron expressions contain six fields in the order `second minute hour day-of-month month day-of-week`. Numeric wildcards, lists, ranges, and steps are supported. Months use `1..12`; weekdays use `0..7`, with both `0` and `7` representing Sunday. Named months and weekdays are not supported.

## Requirements

- Linux x86_64 or ARM64;
- JDK 17 or newer;
- Docker with the Compose plugin or `dcw` for local PostgreSQL;
- `openapi-yagen` available in `PATH`, with OpenAPI 3.2 support in `builtin:kotlin_ktor_server`;
- `libpq` and pthread system headers for the Kotlin/Native build;
- internet access for the initial Gradle and npm dependency downloads.

A Windows target is declared, but the current platform sources and cinterop configuration are Linux-oriented. macOS targets are disabled.

## Quick start

Start PostgreSQL from the project root:

```bash
dcw up
```

Without `dcw`, use Docker Compose directly:

```bash
docker compose -f env/compose.yaml up -d --wait
```

Build and run the server:

```bash
./gradlew :server:linkInstajobDebugExecutableLinuxX64

INSTANTJOB_DATABASE_PASSWORD=test-password \
server/build/bin/linuxX64/instajobDebugExecutable/instajob.kexe
```

By default, the HTTP API listens on `0.0.0.0:8080`, and local PostgreSQL is available at `127.0.0.1:5432`. The development API key is `dev-key`, mapped to the `dev` service.

Stop the environment:

```bash
dcw down
dcw down -p  # Also remove the PostgreSQL volume
```

The equivalent commands are `docker compose -f env/compose.yaml down` and `docker compose -f env/compose.yaml down --volumes`.

## Build and tests

```bash
# Native unit tests
./gradlew :server:linuxX64Test

# The same tests, including the PostgreSQL integration suite
INSTANTJOB_TEST_POSTGRES=true ./gradlew :server:linuxX64Test

# All project checks
./gradlew check

# Dashboard development bundle
./gradlew :dashboard:jsBrowserDevelopmentWebpack
```

Integration tests use PostgreSQL from `env/compose.yaml`: database `instantjob`, user `instantjob`, password `test-password`, address `127.0.0.1:5432`.

## Configuration

Environment variable names are derived from configuration paths and use the `INSTANTJOB_` prefix.

| Variable | Default | Purpose |
|---|---:|---|
| `INSTANTJOB_HOST` | `0.0.0.0` | HTTP server bind address |
| `INSTANTJOB_PORT` | `8080` | HTTP server port |
| `INSTANTJOB_LOGLEVEL` | `DEBUG` | `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR` |
| `INSTANTJOB_APIKEYS` | `dev-key:dev` | comma-separated `api-key:service` pairs |
| `INSTANTJOB_CALLBACKSECRETS` | empty | `secretRef=Authorization value` pairs |
| `INSTANTJOB_CALLBACKALLOWHTTP` | `false` | allow HTTP rather than HTTPS callback URLs |
| `INSTANTJOB_DATABASE_HOST` | `127.0.0.1` | PostgreSQL host |
| `INSTANTJOB_DATABASE_PORT` | `5432` | PostgreSQL port |
| `INSTANTJOB_DATABASE_NAME` | `instantjob` | database name |
| `INSTANTJOB_DATABASE_USER` | `instantjob` | database user |
| `INSTANTJOB_DATABASE_PASSWORD` | — | required database password |
| `INSTANTJOB_DATABASE_POOLSIZE` | `10` | connection pool size |

Passwords, API keys, and secrets are masked in configuration logs.

## API and project layout

See [docs/api.md](docs/api.md) for the complete REST API, scheduling, callback, and pull protocol documentation. The machine-readable source of truth is [api/openapi.yaml](api/openapi.yaml).

```text
api/openapi.yaml             OpenAPI 3.2 contract
env/compose.yaml             local PostgreSQL environment
server/src/commonMain/       API handlers, scheduler, and persistence
server/src/commonTest/       unit and PostgreSQL integration tests
server/src/nativeMain/       native entry point and console logger
dashboard/src/jsMain/        Kotlin/JS dashboard placeholder
```

Gradle generates API code in `server/build/generated/openapi/kotlin`. Generated files must not be edited or committed. Development rules for automated contributors are documented in [AGENTS.md](AGENTS.md).
