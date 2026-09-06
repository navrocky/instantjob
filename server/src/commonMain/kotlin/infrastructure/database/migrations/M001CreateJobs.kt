package infrastructure.database.migrations

import infrastructure.database.InstantJobCatalog
import io.github.kormium.migrate.Migration

internal val m001CreateJobs = Migration<InstantJobCatalog>(
    "001-create-jobs",
    """
        CREATE TABLE IF NOT EXISTS jobs (
            id uuid PRIMARY KEY,
            producer_service varchar(128) NOT NULL,
            target_service varchar(128) NOT NULL,
            task_name varchar(128) NOT NULL,
            idempotency_key varchar(256) NOT NULL,
            request_fingerprint text NOT NULL,
            status varchar(32) NOT NULL,
            delivery_type varchar(32) NOT NULL,
            next_run_at timestamptz,
            execution_count bigint NOT NULL DEFAULT 0,
            body jsonb NOT NULL,
            created_at timestamptz NOT NULL,
            updated_at timestamptz NOT NULL,
            UNIQUE(producer_service, idempotency_key)
        );

        CREATE INDEX IF NOT EXISTS jobs_due_idx
            ON jobs(next_run_at)
            WHERE status = 'ACTIVE';

        CREATE INDEX IF NOT EXISTS jobs_visibility_idx
            ON jobs(producer_service, target_service, created_at, id);
    """.trimIndent(),
)
