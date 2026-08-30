# InstantJob REST API

The public contract is defined in [OpenAPI 3.2](../api/openapi.yaml). This document explains API semantics; if its wire-format details differ from the schema, the OpenAPI document is the source of truth.

## General rules

- Business endpoints use the `/v1` prefix and require the `X-API-Key` header.
- The API key identifies a service. It becomes `producerService` when submitting a job and `targetService` when claiming executions.
- Successful business responses contain `code: "SUCCESS"`.
- Clients may provide `X-Request-Id`; its value is added to the request context.
- Delivery follows at-least-once semantics. Consumers must deduplicate side effects by `executionId`.
- Timestamps use RFC 3339/ISO 8601, for example `2026-08-30T12:00:00Z`.

An error has the following shape:

```json
{
  "code": "NOT_FOUND",
  "message": "Job could not be found",
  "requestId": "request-id"
}
```

The primary error statuses are `400` for an invalid request, `401` for an invalid API key, `403` for denied access, `404` for a missing resource, `409` for an idempotency or lease conflict, and `500` for an internal error.

## Jobs

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/jobs` | submit a job |
| `GET` | `/v1/jobs` | list visible jobs with filters and cursor pagination |
| `GET` | `/v1/jobs/{jobId}` | retrieve a job |
| `DELETE` | `/v1/jobs/{jobId}` | cancel future executions |
| `GET` | `/v1/jobs/{jobId}/executions` | list a job's execution history |
| `GET` | `/v1/executions/{executionId}` | retrieve an execution and its attempts |

### Job submission

```http
POST /v1/jobs
X-API-Key: producer-key
Content-Type: application/json
```

```json
{
  "targetService": "report-worker",
  "taskName": "build-report",
  "idempotencyKey": "daily-report-2026-08-30",
  "parameters": { "reportId": 42 },
  "schedule": {
    "type": "INTERVAL",
    "everySeconds": 3600,
    "maxExecutions": 24
  },
  "delivery": { "type": "PULL" },
  "retryPolicy": {
    "maxAttempts": 5,
    "initialDelaySeconds": 5,
    "multiplier": 2.0,
    "maxDelaySeconds": 300,
    "jitter": 0.2
  },
  "executionPolicy": { "allowOverlap": false }
}
```

`parameters` is a JSON object. A client cannot override `producerService` in the body; it is derived from the API key.

The response contains `created` and the complete job. Repeating an identical request with the same `(producerService, idempotencyKey)` pair returns the original job with `created: false`. A different payload with the same key returns `409 IDEMPOTENCY_CONFLICT`.

### Schedules

- `{"type":"IMMEDIATE"}` runs once immediately.
- `{"type":"AT","runAt":"..."}` runs once at the specified time; a time in the past causes immediate execution.
- `INTERVAL` uses `everySeconds`, an optional `startAt`, and optionally one of `maxExecutions` or `endAt`.
- `CRON` uses a six-field `second minute hour day-of-month month day-of-week` expression, an IANA `timezone`, and an optional `maxExecutions` or `endAt` limit. Numeric wildcards, lists, ranges, and steps are supported. Months use `1..12`; weekdays use `0..7`, with both `0` and `7` representing Sunday. Named months and weekdays are not supported.

A job has exactly one schedule. An interval or cron schedule without a limit continues until cancellation. Overlap is disabled by default, and missed intervals are coalesced instead of producing an execution storm.

### Cancellation and statuses

A job has one of these states: `ACTIVE`, `CANCELLING`, `CANCELLED`, `COMPLETED`, or `FAILED`. Cancellation prevents future executions but does not stop a callback that has already started or an execution with an active lease. In that case, the job temporarily enters `CANCELLING`.

An execution has one of these states: `PENDING`, `LEASED`, `SUCCEEDED`, or `FAILED`. A retry does not create a new execution ID.

## Pull workers

### Claim

```http
POST /v1/executions/claim
X-API-Key: worker-key
Content-Type: application/json
```

```json
{"taskNames":["build-report"],"maxItems":10,"waitSeconds":30}
```

The request uses long polling and atomically returns up to 100 executions assigned to the service identified by the API key. Each item contains an `execution`, an opaque `leaseToken`, and `leaseUntil`.

### Heartbeat

```http
POST /v1/executions/{executionId}/heartbeat
X-API-Key: worker-key
Content-Type: application/json

{"leaseToken":"..."}
```

A heartbeat extends the lease. A foreign, expired, or superseded token returns `409 LEASE_CONFLICT`.

### Completion

```http
POST /v1/executions/{executionId}/complete
X-API-Key: worker-key
Content-Type: application/json
```

```json
{"leaseToken":"...","outcome":"SUCCEEDED","result":{"documentId":"42"}}
```

For a failure, the worker sends `outcome: "FAILED"`, `retryable`, and a concise `error` value. If the lease expires before completion, the job is retried according to its retry policy.

## HTTP callbacks

```json
{
  "delivery": {
    "type": "HTTP_CALLBACK",
    "method": "POST",
    "url": "https://worker.example/jobs",
    "parametersType": "JSON",
    "secretRef": "report-worker-token"
  }
}
```

Supported methods are `GET`, `POST`, and `PUT`. Supported parameter encodings are `QUERY`, `FORM_URLENCODED`, and `JSON`. A `secretRef` is resolved from `INSTANTJOB_CALLBACKSECRETS` and sent as the `Authorization` header; the secret itself is not stored with the job.

Any `2xx` response is considered successful. Network errors, timeouts, `408`, `429`, and `5xx` responses are retried by default; the set of retryable statuses can be extended. Callbacks receive the `X-InstantJob-Execution-Id`, `X-InstantJob-Attempt`, and `X-InstantJob-Job-Id` headers.

## Retry policy

| Field | Default |
|---|---:|
| `maxAttempts` | `5` |
| `initialDelaySeconds` | `5` |
| `multiplier` | `2.0` |
| `maxDelaySeconds` | `300` |
| `jitter` | `0.2` |

`maxAttempts` includes the initial attempt. An expired pull lease fails the current attempt and counts toward this limit.

## Service endpoints

- `GET /health/live` reports that the process is running.
- `GET /health/ready` reports that PostgreSQL is available.

These endpoints do not require an API key.
