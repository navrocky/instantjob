CREATE TABLE IF NOT EXISTS jobs
(
    id
    uuid
    PRIMARY
    KEY,
    producer_service
    varchar
(
    128
) NOT NULL,
    target_service varchar
(
    128
) NOT NULL,
    task_name varchar
(
    128
) NOT NULL,
    idempotency_key varchar
(
    256
) NOT NULL,
    request_fingerprint text NOT NULL,
    status varchar
(
    32
) NOT NULL,
    delivery_type varchar
(
    32
) NOT NULL,
    next_run_at timestamptz,
    execution_count bigint NOT NULL DEFAULT 0,
    body jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE
(
    producer_service,
    idempotency_key
)
    );

CREATE INDEX IF NOT EXISTS jobs_due_idx ON jobs (next_run_at) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS jobs_visibility_idx ON jobs (producer_service, target_service, created_at, id);

CREATE TABLE IF NOT EXISTS executions
(
    id
    uuid
    PRIMARY
    KEY,
    job_id
    uuid
    NOT
    NULL
    REFERENCES
    jobs
(
    id
),
    target_service varchar
(
    128
) NOT NULL,
    task_name varchar
(
    128
) NOT NULL,
    delivery_type varchar
(
    32
) NOT NULL,
    status varchar
(
    32
) NOT NULL,
    scheduled_at timestamptz NOT NULL,
    lease_token uuid,
    lease_until timestamptz,
    body jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
    );

CREATE INDEX IF NOT EXISTS executions_claim_idx
    ON executions (target_service, task_name, scheduled_at, id)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS executions_lease_idx ON executions (lease_until) WHERE status = 'LEASED';
CREATE INDEX IF NOT EXISTS executions_job_idx ON executions (job_id, created_at, id);

CREATE TABLE IF NOT EXISTS attempts
(
    execution_id
    uuid
    NOT
    NULL
    REFERENCES
    executions
(
    id
),
    attempt_number integer NOT NULL,
    body jsonb NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    PRIMARY KEY
(
    execution_id,
    attempt_number
)
    );

CREATE INDEX IF NOT EXISTS attempts_retention_idx ON attempts (started_at);
