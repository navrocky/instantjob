package features.executions

import infrastructure.database.InstantJobCatalog
import infrastructure.database.PersistenceJson
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.Scope

@OptIn(DelicateKormiumApi::class)
class PostgresAttemptRepository(
    private val scope: Scope<InstantJobCatalog>
) : AttemptRepository {
    override fun listByExecution(executionId: String): List<Attempt> = scope.execute(
        "SELECT body::text FROM attempts WHERE execution_id=CAST(:id AS uuid) ORDER BY attempt_number",
        mapOf("id" to executionId),
        emptyList(),
    ) { PersistenceJson.decodeAttempt(it.getString(0)!!) }

    override fun insert(executionId: String, attempt: Attempt) {
        scope.executeUpdate(
            "INSERT INTO attempts(execution_id,attempt_number,body,started_at) VALUES(CAST(:id AS uuid),:number,CAST(:body AS jsonb),CAST(:started AS timestamptz))",
            mapOf(
                "id" to executionId, "number" to attempt.number,
                "body" to PersistenceJson.encode(attempt), "started" to attempt.startedAt.toString(),
            ),
            emptyList(),
        )
    }

    override fun update(executionId: String, attempt: Attempt) {
        scope.executeUpdate(
            "UPDATE attempts SET body=CAST(:body AS jsonb),finished_at=CAST(:finished AS timestamptz) WHERE execution_id=CAST(:id AS uuid) AND attempt_number=:number",
            mapOf(
                "body" to PersistenceJson.encode(attempt), "finished" to attempt.finishedAt?.toString(),
                "id" to executionId, "number" to attempt.number,
            ),
            emptyList(),
        )
    }
}
