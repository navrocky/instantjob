package infrastructure.database.migrations

import infrastructure.database.InstantJobCatalog
import io.github.kormium.migrate.Migration

internal val m003CreateAttempts = Migration<InstantJobCatalog>(
    id = "003-create-attempts",
    sql = """
        CREATE TABLE IF NOT EXISTS attempts (
            execution_id uuid NOT NULL REFERENCES executions(id),
            attempt_number integer NOT NULL,
            body jsonb NOT NULL,
            started_at timestamptz NOT NULL,
            finished_at timestamptz,
            PRIMARY KEY(execution_id, attempt_number)
        );

        CREATE INDEX IF NOT EXISTS attempts_retention_idx
            ON attempts(started_at);
    """.trimIndent(),
)
