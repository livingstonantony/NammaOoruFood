package dev.livin.nammaoorufood.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.livin.nammaoorufood.config.DatabaseConfig
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object DatabaseFactory {
    fun createDataSource(config: DatabaseConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                // Schema timestamps are UTC; pin the session so CURRENT_TIMESTAMP defaults match.
                connectionInitSql = "SET time_zone = '+00:00'"
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            },
        )

    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
