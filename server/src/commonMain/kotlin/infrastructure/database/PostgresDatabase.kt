package infrastructure.database

import infrastructure.database.migrations.instantJobMigrations
import io.github.kormium.Catalog
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.migrate.migrate

object InstantJobCatalog : Catalog

/** PostgreSQL connection and pool settings. */
data class DatabaseConfig(
    /** Database server host name or address. */
    val host: String,
    /** Database server TCP port. */
    val port: Int,
    /** Database name. */
    val name: String,
    /** Database user name. */
    val user: String,
    /** Database user password. */
    val password: String,
    /** Maximum number of pooled connections. */
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
        block = {
            beforeStart {
                @Suppress("UNCHECKED_CAST")
                (this as Database<InstantJobCatalog>).migrate(instantJobMigrations)
            }
        },
    )

    @OptIn(DelicateKormiumApi::class)
    fun ping(): Boolean = runCatching {
        handle.autocommit {
            execute("SELECT 1", emptyMap(), emptyList()) { it.getInt(0) }.single() == 1
        }
    }.getOrDefault(false)

    override fun close() = handle.close()
}
