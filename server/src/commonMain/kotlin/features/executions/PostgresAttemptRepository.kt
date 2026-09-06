package features.executions

import infrastructure.database.InstantJobCatalog
import io.github.kormium.Scope
import io.github.kormium.and
import io.github.kormium.eq
import kotlin.uuid.Uuid

/** Stores execution attempts in PostgreSQL through a transaction-bound Kormium scope. */
class PostgresAttemptRepository(
    private val scope: Scope<InstantJobCatalog>,
) : AttemptRepository {
    override fun listByExecution(executionId: String): List<Attempt> = with(scope) {
        AttemptsTable.find {
            where { AttemptsTable.executionId eq Uuid.parse(executionId) }
            orderBy ASC AttemptsTable.attemptNumber
        }
    }.map(AttemptEntity::toDomain)

    override fun insert(executionId: String, attempt: Attempt) {
        with(scope) { AttemptsTable.insert(attempt.toEntity(executionId)) }
    }

    override fun update(executionId: String, attempt: Attempt) {
        val executionUuid = Uuid.parse(executionId)
        val affected = with(scope) {
            AttemptsTable.update(attempt.toUpdateEntity()) {
                where {
                    (AttemptsTable.executionId eq executionUuid) and
                            (AttemptsTable.attemptNumber eq attempt.number)
                }
            }
        }
        check(affected == 1L) { "Attempt does not exist: executionId=$executionId, number=${attempt.number}" }
    }
}
