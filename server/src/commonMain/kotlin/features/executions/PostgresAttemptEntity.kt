package features.executions

import infrastructure.database.InstantJobCatalog
import infrastructure.database.databaseJson
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.jsonColumnType
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

internal object AttemptsTable : Table<InstantJobCatalog, AttemptEntity>("attempts", ::AttemptEntity) {
    val executionId by Column.UUID("execution_id").primaryKey()
    val attemptNumber by Column.Int("attempt_number").primaryKey()
    val body by Column.of(jsonColumnType<PersistedAttempt>(databaseJson))
    val startedAt by Column.Instant("started_at")
    val finishedAt by Column.Instant("finished_at").nullable()
}

internal class AttemptEntity : Entity() {
    var executionId by AttemptsTable.executionId
    var attemptNumber by AttemptsTable.attemptNumber
    var body by AttemptsTable.body
    var startedAt by AttemptsTable.startedAt
    var finishedAt by AttemptsTable.finishedAt
}

/** Serializable snapshot stored in the attempt JSON column. */
@Serializable
internal data class PersistedAttempt(
    /** One-based attempt number. */
    val number: Int,
    /** ISO-8601 time at which the attempt started. */
    val startedAt: String,
    /** ISO-8601 completion time, if the attempt has finished. */
    val finishedAt: String? = null,
    /** HTTP response status returned by callback delivery, if available. */
    val httpStatus: Int? = null,
    /** Failure details recorded for the attempt. */
    val error: String? = null,
)

internal fun Attempt.toEntity(executionId: String): AttemptEntity = AttemptEntity().also {
    it.executionId = Uuid.parse(executionId)
    it.attemptNumber = number
    it.body = toPersisted()
    it.startedAt = startedAt
    it.finishedAt = finishedAt
}

internal fun Attempt.toUpdateEntity(): AttemptEntity = AttemptEntity().also {
    it.body = toPersisted()
    it.finishedAt = finishedAt
}

internal fun AttemptEntity.toDomain() = Attempt(
    number = attemptNumber,
    startedAt = startedAt,
    finishedAt = finishedAt,
    httpStatus = body.httpStatus,
    error = body.error,
)

private fun Attempt.toPersisted() = PersistedAttempt(
    number, startedAt.toString(), finishedAt?.toString(), httpStatus, error,
)
