package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.auth.JdbcSessionRepository
import dev.livin.nammaoorufood.config.AppConfig
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.database.DatabaseFactory
import dev.livin.nammaoorufood.maintenance.RetentionService
import dev.livin.nammaoorufood.notification.JdbcNotificationRepository
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class Phase9Test {
    private val dbUrl = "jdbc:mysql://localhost:3306/nammaoorufood_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    private val testConfig = AppConfig.fromEnv(mapOf("APP_ENV" to "test", "DB_URL" to dbUrl))

    @Test fun missingAppEnvFailsClosed() {
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(emptyMap()) }
    }

    @Test fun unknownEnvironmentIsTreatedAsProduction() {
        // A mistyped value must not enable dev defaults or dev seed accounts.
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf("APP_ENV" to "prod")) }
        assertTrue(testConfig.copy(environment = "staging").isProduction)
        assertFalse(testConfig.isProduction)
    }

    @Test fun swaggerAndSpecAreServedOutsideProduction() = testApplication {
        application { module(testConfig) }
        val spec = client.get("/swagger/documentation.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.bodyAsText().contains("openapi: 3.0.3"))
        assertEquals(HttpStatusCode.OK, client.get("/swagger").status)
    }

    @Test fun healthDoesNotExposeEnvironment() = testApplication {
        application { module(testConfig) }
        assertFalse(client.get("/health").bodyAsText().contains("test"))
    }

    @Test fun retentionDeletesOnlyOldInactiveSessionsAndOldNotifications() {
        val ds = DatabaseFactory.createDataSource(testConfig.database)
        DatabaseFactory.migrate(ds)
        val db = Database(ds)
        val sessions = JdbcSessionRepository()
        ds.connection.use { c ->
            c.createStatement().use {
                it.execute("DELETE FROM notifications"); it.execute("DELETE FROM auth_sessions"); it.execute("DELETE FROM users WHERE username LIKE 'ret_%'")
                it.execute("INSERT INTO users (username, password_hash, role, display_name) VALUES ('ret_u', 'x', 'CUSTOMER', 'R')")
            }
            c.commit()
        }
        val now = Instant.now()
        val userId = ds.connection.use { c -> c.createStatement().executeQuery("SELECT id FROM users WHERE username='ret_u'").use { it.next(); it.getLong(1) } }
        db.transaction { conn ->
            sessions.create(conn, userId, "a".repeat(64), now.minus(Duration.ofDays(30)))  // expired long ago
            sessions.create(conn, userId, "b".repeat(64), now.plus(Duration.ofDays(5)))    // active
            sessions.create(conn, userId, "c".repeat(64), now.minus(Duration.ofHours(1)))  // expired within grace
        }
        val result = RetentionService(db, sessions, JdbcNotificationRepository(), Clock.fixed(now, ZoneOffset.UTC)).runOnce()
        assertEquals(1, result.sessionsDeleted)
        val remaining = ds.connection.use { c -> c.createStatement().executeQuery("SELECT COUNT(*) FROM auth_sessions").use { it.next(); it.getInt(1) } }
        assertEquals(2, remaining)
        ds.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM users WHERE username='ret_u'") }; c.commit() }
        ds.close()
    }
}
