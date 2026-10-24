package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.config.AppConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.sql.DriverManager
import kotlin.test.*

class ApplicationTest {

    private val testConfig = AppConfig.fromEnv(
        mapOf(
            "APP_ENV" to "test",
            "DB_URL" to "jdbc:mysql://localhost:3306/nammaoorufood_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        ),
    )

    @Test
    fun healthReportsUpAndMigratesSchema() = testApplication {
        application { module(testConfig) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"UP\""))

        DriverManager.getConnection(testConfig.database.url, testConfig.database.user, testConfig.database.password).use { c ->
            val tables = c.createStatement().executeQuery("SHOW TABLES").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
            }
            assertTrue(tables.containsAll(listOf("users", "hotels", "orders", "order_items", "pickup_tokens")))
        }
    }

    @Test
    fun productionRequiresExplicitDatabaseConfig() {
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf("APP_ENV" to "production")) }
    }
}
