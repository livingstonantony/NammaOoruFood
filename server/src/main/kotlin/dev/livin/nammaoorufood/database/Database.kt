package dev.livin.nammaoorufood.database

import java.sql.Connection
import javax.sql.DataSource

class Database(private val dataSource: DataSource) {
    // Runs the block in one transaction: commit on success, rollback on any failure.
    fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            try {
                block(conn).also { conn.commit() }
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
}
