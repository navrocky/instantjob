# AGENTS.md

## Project overview

InstantJob is an early Kotlin Multiplatform prototype of a language-agnostic background-job service. It has two Gradle subprojects:

- `server`: Kotlin/Native Ktor/CIO HTTP API and scheduling domain code.
- `dashboard`: Kotlin/JS browser application; currently only a placeholder.

The active API uses generated Ktor routes and models from `api/openapi.yaml`. `DefaultSchedulerService` contains orchestration and domain rules. Production persistence is split into PostgreSQL repositories by table; common tests use matching mocked repositories.

## Repository map

- `server/src/commonMain/kotlin/CommonMain.kt`: server composition, Ktor plugins, configuration, and error mapping.
- `api/openapi.yaml`: OpenAPI 3.2 source of truth for the public API.
- `server/src/commonMain/kotlin/features/`: flat feature slices containing domain models, application services, core ports, and their feature-specific adapters.
- `server/src/commonMain/kotlin/presentation/rest/`: REST handlers and explicit generated-DTO/domain mappings.
- `server/src/commonMain/kotlin/infrastructure/database/`: shared PostgreSQL connection and persistence mapping infrastructure; feature-specific adapters are colocated with their repository interfaces.
- `server/src/commonMain/kotlin/infrastructure/database/migrations/`: ordered Kormium migrations, one migration per file; applied migrations are immutable.
- `server/src/commonMain/kotlin/infrastructure/http/`: Ktor HTTP and callback-delivery adapters.
- `server/src/commonMain/kotlin/common/`: request context, IDs, and JSON response envelopes.
- `server/src/commonMain/kotlin/infrastructure/config/`: hierarchical configuration and environment backend.
- `server/src/commonMain/kotlin/infrastructure/log/`: multiplatform logging abstraction.
- `server/src/nativeMain/`: native entry point and console logging backend.
- `server/src/linuxMain/`: Linux `actual` implementation for environment access.
- `server/src/nativeInterop/cinterop/pthread.def`: pthread cinterop used by native logging.
- `server/src/commonTest/`: Kotlin tests and mocked repository implementations.
- `dashboard/src/jsMain/`: browser entry point and static resources.

## Build and verification

Run commands from the repository root and use the checked-in Gradle Wrapper.

```bash
./gradlew check
./gradlew :server:linkInstajobDebugExecutableLinuxX64
./gradlew :dashboard:jsBrowserDevelopmentWebpack
```

Do not report verification as successful unless the commands actually complete.

When changing only one module, run its narrowest relevant task first, then `./gradlew check` before handoff. Do not edit or regenerate `gradle-wrapper.jar` manually.

## Architecture and behavior

Keep reusable server logic in `commonMain`; platform calls belong in platform source sets behind `expect`/`actual` declarations.

Follow Clean Architecture and keep dependency direction pointing toward the core:

- Organize source code as flat feature slices. Do not add generic `models/`, `services/`, or `repository/` directories inside a feature; distinguish responsibilities with clear type names instead. Each feature owns its domain models, application services, and core ports. Put only genuinely shared primitives and models in `common`.
- The core consists of domain models, services, and repository interfaces. Core code must be plain Kotlin and must not depend on Ktor, Kormium, generated OpenAPI types, serialization frameworks, database drivers, or other external frameworks. `kotlin.time` and `kotlinx.datetime` are explicitly allowed in the core for instants, durations, clocks, calendar calculations, time zones, and scheduling rules.
- Domain models must be declared explicitly. Never use OpenAPI-generated classes as domain models.
- Treat OpenAPI-generated classes as transport DTOs only. The presentation layer must map DTOs to domain models before invoking a service and map domain results back to DTOs.
- Implement persistence through repository interfaces owned by the core. Repositories for persistent entities must have at least a `Postgres*Repository` production adapter and a `Mocked*Repository` test adapter. Repositories backed by application configuration must have a `Configured*Repository` production adapter and a `Mocked*Repository` test adapter.
- PostgreSQL implementations and transaction infrastructure belong to infrastructure adapters. Keep REST in `presentation/rest`; Ktor handlers, routes, authentication extraction, DTO mapping, and HTTP error mapping belong there. Additional interfaces such as GraphQL must receive their own sibling presentation package.
- Use dependency injection throughout the project. Pass every dependency explicitly through a constructor; do not use service locators, global dependency registries, hidden singleton dependencies, or field injection.
- Do not bypass layers: presentation calls services, services operate on domain models and repository interfaces, and persistence adapters implement those interfaces.

The request flow is:

1. Generated `*ApiRoutes` parse and validate transport DTOs from the OpenAPI contract.
2. Handwritten presentation adapters authenticate the service and map generated DTOs to domain models.
3. Application services apply scheduling, idempotency, lease, retry, and state-transition rules using repository interfaces.
4. Entity repository adapters access exactly one table each; PostgreSQL implementations share the active transaction.
5. Presentation adapters map domain results and errors back to generated response DTOs.

Preserve these existing API rules unless a task explicitly changes the contract:

- Every business response contains `code`; errors follow generated `ErrorResponse`.
- `X-API-Key` identifies producer/target services.
- `(producerService, idempotencyKey)` returns the existing job only for an identical request; a changed request is a conflict.
- Only the producer can cancel a job; terminal cancellation is idempotent and an active lease transitions the job through `CANCELLING`.
- Environment variables use the `INSTANTJOB_` prefix and uppercase underscore-separated paths.

## Kotlin conventions

- Follow the official Kotlin style configured in `gradle.properties`.
- Keep package names lowercase and organize code by feature, following the current layout.
- Prefer immutable data classes and constructor-injected dependencies.
- Define entity persistence through a table-specific repository interface. Provide a `Postgres*Repository` implementation in `commonMain` and a `Mocked*Repository` implementation in `commonTest`. For configuration-backed data, provide corresponding `Configured*Repository` and `Mocked*Repository` implementations.
- Use `kotlinx.serialization` only for transport or persistence DTOs. Domain models must remain serialization-independent.
- Use `kotlin.time` for domain instants, durations, and clocks. Use `kotlinx.datetime` for multiplatform calendar and time-zone calculations, including cron scheduling. Avoid JVM-only APIs in `commonMain`.
- Always call `delay` with a `Duration`, for example `delay(100.milliseconds)`, rather than using the millisecond-number overload.
- Log through `log.logger`; include the request context in service logs where available.
- Existing error messages contain searchable identifiers such as `<07e63b5e>`. Preserve them when editing an existing error path and use a new unique identifier for a new diagnostic path.
- Avoid broad cleanup or formatting in files that contain unrelated user changes.

## Testing expectations

- Add common tests under `server/src/commonTest/kotlin` for shared behavior.
- When changing serialized models, test both encoding and decoding and verify discriminator names and optional fields.
- When changing scheduler behavior, cover idempotency, missing jobs, and allowed/forbidden status transitions.
- When changing routes, verify success envelopes, HTTP status mapping, invalid input, and `X-Request-Id` handling.
- Tests must not rely on order or data left by another test; create a fresh repository/service fixture.

## Documentation

Keep `README.md` and `api/openapi.yaml` aligned with implemented behavior. Change the OpenAPI contract first, regenerate into `build/generated`, and never edit or commit generated Kotlin sources.
