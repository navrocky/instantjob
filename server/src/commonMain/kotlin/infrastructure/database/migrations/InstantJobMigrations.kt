package infrastructure.database.migrations

import infrastructure.database.InstantJobCatalog
import io.github.kormium.migrate.Migration

internal val instantJobMigrations: List<Migration<InstantJobCatalog>> = listOf(
    m001CreateJobs,
    m002CreateExecutions,
    m003CreateAttempts,
)
