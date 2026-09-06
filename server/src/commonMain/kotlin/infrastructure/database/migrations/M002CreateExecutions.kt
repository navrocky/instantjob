package infrastructure.database.migrations

import infrastructure.database.InstantJobCatalog
import io.github.kormium.migrate.Migration

internal val m002CreateExecutions = Migration<InstantJobCatalog>(
    "002-create-executions",
    """
        CREATE TABLE IF NOT EXISTS executions (
            id uuid PRIMARY KEY,
            job_id uuid NOT NULL REFERENCES jobs(id),
            target_service varchar(128) NOT NULL,
            task_name varchar(128) NOT NULL,
            delivery_type varchar(32) NOT NULL,
            status varchar(32) NOT NULL,
            scheduled_at timestamptz NOT NULL,
            lease_token uuid,
            lease_until timestamptz,
            body jsonb NOT NULL,
            created_at timestamptz NOT NULL,
            updated_at timestamptz NOT NULL
        );

        CREATE INDEX IF NOT EXISTS executions_claim_idx
            ON executions(target_service, task_name, scheduled_at, id)
            WHERE status = 'PENDING';

        CREATE INDEX IF NOT EXISTS executions_lease_idx
            ON executions(lease_until)
            WHERE status = 'LEASED';

        CREATE INDEX IF NOT EXISTS executions_job_idx
            ON executions(job_id, created_at, id);
    """.trimIndent(),
)
