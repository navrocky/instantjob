package features.executions

import infrastructure.database.InstantJobCatalog
import infrastructure.database.databaseJson
import infrastructure.database.toJsonContainer
import infrastructure.database.toJsonObject
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.jsonColumnType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

internal object ExecutionsTable : Table<InstantJobCatalog, ExecutionEntity>("executions", ::ExecutionEntity) {
    val id by Column.UUID().primaryKey()
    val jobId by Column.UUID("job_id")
    val targetService by Column.Text("target_service")
    val taskName by Column.Text("task_name")
    val deliveryType by Column.Text("delivery_type")
    val status by Column.enum<ExecutionStatus>()
    val scheduledAt by Column.Instant("scheduled_at")
    val leaseToken by Column.UUID("lease_token").nullable()
    val leaseUntil by Column.Instant("lease_until").nullable()
    val body by Column.of(jsonColumnType<PersistedExecution>(databaseJson))
    val createdAt by Column.Instant("created_at")
    val updatedAt by Column.Instant("updated_at")
}

internal class ExecutionEntity : Entity() {
    var id by ExecutionsTable.id
    var jobId by ExecutionsTable.jobId
    var targetService by ExecutionsTable.targetService
    var taskName by ExecutionsTable.taskName
    var deliveryType by ExecutionsTable.deliveryType
    var status by ExecutionsTable.status
    var scheduledAt by ExecutionsTable.scheduledAt
    var leaseToken by ExecutionsTable.leaseToken
    var leaseUntil by ExecutionsTable.leaseUntil
    var body by ExecutionsTable.body
    var createdAt by ExecutionsTable.createdAt
    var updatedAt by ExecutionsTable.updatedAt
}

/** Serializable execution snapshot stored in the execution JSON column. */
@Serializable
internal data class PersistedExecution(
    /** Execution identifier. */
    val id: String,
    /** Source job identifier. */
    val jobId: String,
    /** Service that registered the source job. */
    val producerService: String,
    /** Service responsible for executing the task. */
    val targetService: String,
    /** Task identifier understood by the target service. */
    val taskName: String,
    /** JSON task parameters. */
    val parameters: JsonObject,
    /** ISO-8601 planned execution time. */
    val scheduledAt: String,
    /** Persisted execution status name. */
    val status: String,
    /** Number of attempts started for the execution. */
    val attemptCount: Int,
    /** ISO-8601 lease expiration time, if leased. */
    val leaseUntil: String? = null,
    /** JSON result produced by a successful execution. */
    val result: JsonObject? = null,
    /** Latest execution failure details. */
    val error: String? = null,
    /** ISO-8601 creation time. */
    val createdAt: String,
    /** ISO-8601 time of the latest state change. */
    val updatedAt: String,
)

internal fun Execution.toEntity(deliveryType: String): ExecutionEntity = ExecutionEntity().also {
    it.id = Uuid.parse(id)
    it.jobId = Uuid.parse(jobId)
    it.targetService = targetService
    it.taskName = taskName
    it.deliveryType = deliveryType
    it.status = status
    it.scheduledAt = scheduledAt
    it.leaseToken = null
    it.leaseUntil = leaseUntil
    it.body = toPersisted()
    it.createdAt = createdAt
    it.updatedAt = updatedAt
}

internal fun Execution.toUpdateEntity(leaseToken: String?): ExecutionEntity = ExecutionEntity().also {
    it.status = status
    it.scheduledAt = scheduledAt
    it.leaseToken = leaseToken?.let(Uuid::parse)
    it.leaseUntil = leaseUntil
    it.body = toPersisted()
    it.updatedAt = updatedAt
}

internal fun ExecutionEntity.toStoredExecution(): StoredExecution = StoredExecution(
    execution = Execution(
        id = id.toString(),
        jobId = jobId.toString(),
        producerService = body.producerService,
        targetService = targetService,
        taskName = taskName,
        parameters = body.parameters.toJsonContainer(),
        scheduledAt = scheduledAt,
        status = status,
        attemptCount = body.attemptCount,
        leaseUntil = leaseUntil,
        result = body.result?.toJsonContainer(),
        error = body.error,
        createdAt = createdAt,
        updatedAt = updatedAt,
    ),
    leaseToken = leaseToken?.toString(),
)

private fun Execution.toPersisted() = PersistedExecution(
    id = id,
    jobId = jobId,
    producerService = producerService,
    targetService = targetService,
    taskName = taskName,
    parameters = parameters.toJsonObject(),
    scheduledAt = scheduledAt.toString(),
    status = status.name,
    attemptCount = attemptCount,
    leaseUntil = leaseUntil?.toString(),
    result = result?.toJsonObject(),
    error = error,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
