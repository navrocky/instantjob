# AGENTS.md

## Project overview

InstantJob is an early Kotlin Multiplatform prototype of a language-agnostic background-job service. It has two Gradle subprojects:

- `server`: Kotlin/Native Ktor/CIO HTTP API and scheduling domain code.
- `dashboard`: Kotlin/JS browser application; currently only a placeholder.

The active API uses generated Ktor routes and models from `api/openapi.yaml`. Production uses `PostgresSchedulerRepository`; `SchedulerStore` is retained as an in-memory unit-test implementation.

## Repository map

- `server/src/commonMain/kotlin/CommonMain.kt`: server composition, Ktor plugins, configuration, and error mapping.
- `api/openapi.yaml`: OpenAPI 3.2 source of truth for the public API.
- `server/src/commonMain/kotlin/api/`: handwritten implementations of generated handler interfaces.
- `server/src/commonMain/kotlin/scheduler/`: scheduling, leases, retries, callbacks, and repository contract.
- `server/src/commonMain/kotlin/common/`: request context, IDs, and JSON response envelopes.
- `server/src/commonMain/kotlin/config/`: hierarchical configuration and environment backend.
- `server/src/commonMain/kotlin/log/`: multiplatform logging abstraction.
- `server/src/nativeMain/`: native entry point and console logging backend.
- `server/src/linuxMain/`: Linux `actual` implementation for environment access.
- `server/src/nativeInterop/cinterop/pthread.def`: pthread cinterop used by native logging.
- `server/src/commonTest/`: Kotest tests.
- `dashboard/src/jsMain/`: browser entry point and static resources.

## Build and verification

Run commands from the repository root and use the checked-in Gradle Wrapper.

```bash
./gradlew check
./gradlew :server:linkInstajobDebugExecutableLinuxX64
./gradlew :dashboard:jsBrowserDevelopmentWebpack
```

Do not report verification as successful unless the commands actually complete. The native entry point currently has `commonMain()` commented out, so a built server does not start Ktor.

When changing only one module, run its narrowest relevant task first, then `./gradlew check` before handoff. Do not edit or regenerate `gradle-wrapper.jar` manually.

## Architecture and behavior

Keep reusable server logic in `commonMain`; platform calls belong in platform source sets behind `expect`/`actual` declarations.

The request flow is:

1. Generated `*ApiRoutes` parse and validate requests from the OpenAPI contract.
2. Handwritten handlers authenticate the service and call `SchedulerRepository`.
3. `SchedulerStore` applies scheduling, idempotency, lease, retry, and state-transition rules.
4. Ktor `StatusPages` converts thrown exceptions into `JsonErrorResponse`.

Preserve these existing API rules unless a task explicitly changes the contract:

- Every business response contains `code`; errors follow generated `ErrorResponse`.
- `X-API-Key` identifies producer/target services.
- `(producerService, idempotencyKey)` returns the existing job only for an identical request; a changed request is a conflict.
- Only an `ENQUEUED` job can be cancelled.
- Environment variables use the `INSTANTJOB_` prefix and uppercase underscore-separated paths.

## Kotlin conventions

- Follow the official Kotlin style configured in `gradle.properties`.
- Keep package names lowercase and organize code by feature, following the current layout.
- Prefer immutable data classes and constructor-injected dependencies.
- Use `kotlinx.serialization` for API models; sealed polymorphic variants require stable `@SerialName` values.
- Use `kotlin.time` and multiplatform APIs in shared code. Avoid JVM-only APIs in `commonMain`.
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
