package infrastructure.database

import infrastructure.database.migrations.instantJobMigrations
import io.github.kormium.Catalog
import io.github.kormium.DelicateKormiumApi
import io.github.kormium.autocommit
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.migrate.migrate

object InstantJobCatalog : Catalog

/**
 * PostgreSQL connection and pool settings.
 *
 * @property host Database server host name or address.
 * @property port Database server TCP port.
 * @property name Database name.
 * @property user Database user name.
 * @property password Database user password.
 * @property poolSize Maximum number of pooled connections.
 */
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
