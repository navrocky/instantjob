package persistence

import io.github.kormium.Catalog
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase

object InstantJobCatalog : Catalog

data class DatabaseConfig(
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
    val poolSize: Int,
)

class PostgresDatabase(config: DatabaseConfig) : AutoCloseable {
    val handle: Database<InstantJobCatalog> = createDatabase(
        host = config.host,
        port = config.port,
        database = config.name,
        user = config.user,
        password = config.password,
        poolSize = config.poolSize,
    )

    @OptIn(DelicateKormiumApi::class)
    fun migrate() {
        handle.autocommit {
            MIGRATION.split(';').map(String::trim).filter(String::isNotEmpty).forEach { statement ->
                executeUpdate(statement, emptyMap(), emptyList())
            }
        }
    }

    @OptIn(DelicateKormiumApi::class)
    fun ping(): Boolean = runCatching {
        handle.autocommit {
            execute("SELECT 1", emptyMap(), emptyList()) { it.getInt(0) }.single() == 1
        }
    }.getOrDefault(false)

    override fun close() = handle.close()
}

private val MIGRATION = """
CREATE TABLE IF NOT EXISTS jobs (
 id uuid PRIMARY KEY, producer_service varchar(128) NOT NULL, target_service varchar(128) NOT NULL,
 task_name varchar(128) NOT NULL, idempotency_key varchar(256) NOT NULL, request_fingerprint text NOT NULL,
 status varchar(32) NOT NULL, delivery_type varchar(32) NOT NULL, next_run_at timestamptz,
 execution_count bigint NOT NULL DEFAULT 0, body jsonb NOT NULL, created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL, UNIQUE(producer_service,idempotency_key)
);
CREATE INDEX IF NOT EXISTS jobs_due_idx ON jobs(next_run_at) WHERE status='ACTIVE';
CREATE TABLE IF NOT EXISTS executions (
 id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES jobs(id), target_service varchar(128) NOT NULL,
 task_name varchar(128) NOT NULL, delivery_type varchar(32) NOT NULL, status varchar(32) NOT NULL,
 scheduled_at timestamptz NOT NULL, lease_token uuid, lease_until timestamptz, body jsonb NOT NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS executions_claim_idx ON executions(target_service,task_name,scheduled_at,id) WHERE status='PENDING';
CREATE INDEX IF NOT EXISTS executions_lease_idx ON executions(lease_until) WHERE status='LEASED';
CREATE INDEX IF NOT EXISTS executions_job_idx ON executions(job_id,created_at,id);
CREATE TABLE IF NOT EXISTS attempts (
 execution_id uuid NOT NULL REFERENCES executions(id), attempt_number integer NOT NULL, body jsonb NOT NULL,
 started_at timestamptz NOT NULL, finished_at timestamptz, PRIMARY KEY(execution_id,attempt_number)
);
""".trimIndent()
