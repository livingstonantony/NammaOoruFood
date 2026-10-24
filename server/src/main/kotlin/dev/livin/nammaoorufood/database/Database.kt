package dev.livin.nammaoorufood.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import javax.sql.DataSource
import org.jetbrains.exposed.sql.Database as ExposedDatabase
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class Database(private val dataSource: DataSource) {
    private val exposedDatabase = ExposedDatabase.connect(
        getNewConnection = { dataSource.connection },
        databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
    )

    fun <T> transaction(block: (Connection) -> T): T = runBlocking {
        newSuspendedTransaction(Dispatchers.IO, db = exposedDatabase) {
            block(connection.connection as Connection)
        }
    }
}
