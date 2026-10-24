package dev.livin.nammaoorufood.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.livin.nammaoorufood.config.DatabaseConfig
import org.flywaydb.core.Flyway

object DatabaseFactory {
    fun createDataSource(config: DatabaseConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                minimumIdle = minOf(2, config.maxPoolSize)
                isAutoCommit = false
                poolName = "namma-ooru-food-db"
                connectionTimeout = 10_000
                validationTimeout = 5_000
                initializationFailTimeout = 10_000
                connectionInitSql = "SET time_zone = '+00:00'"
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            },
        )

    fun migrate(config: DatabaseConfig) {
        Flyway.configure()
            .dataSource(config.url, config.user, config.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun validateConnection(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            check(connection.isValid(5)) { "Database connection validation failed" }
        }
    }
}
