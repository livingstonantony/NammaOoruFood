package dev.livin.nammaoorufood

import com.zaxxer.hikari.HikariDataSource
import dev.livin.nammaoorufood.config.AppConfig
import dev.livin.nammaoorufood.admin.AdminService
import dev.livin.nammaoorufood.admin.ExposedAdminRepository
import dev.livin.nammaoorufood.admin.adminRoutes
import dev.livin.nammaoorufood.auth.*
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.database.DatabaseFactory
import dev.livin.nammaoorufood.hotel.HotelService
import dev.livin.nammaoorufood.hotel.ExposedHotelRepository
import dev.livin.nammaoorufood.hotel.hotelRoutes
import dev.livin.nammaoorufood.notification.ExposedNotificationRepository
import dev.livin.nammaoorufood.notification.NotificationService
import dev.livin.nammaoorufood.notification.notificationRoutes
import dev.livin.nammaoorufood.order.ExposedOrderRepository
import dev.livin.nammaoorufood.order.OrderService
import dev.livin.nammaoorufood.order.orderRoutes
import dev.livin.nammaoorufood.user.ExposedUserRepository
import dev.livin.nammaoorufood.plugins.configureErrorHandling
import dev.livin.nammaoorufood.plugins.configureSerialization
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.*
import dev.livin.nammaoorufood.maintenance.RetentionService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Duration

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }
        .start(wait = true)
}

@Serializable
data class HealthResponse(val status: String)

fun Application.module(config: AppConfig = AppConfig.fromEnv()) {
    DatabaseFactory.migrate(config.database)
    val dataSource: HikariDataSource = DatabaseFactory.createDataSource(config.database)
    try {
        DatabaseFactory.validateConnection(dataSource)
    } catch (failure: Throwable) {
        dataSource.close()
        throw failure
    }
    monitor.subscribe(ApplicationStopped) { dataSource.close() }

    val db = try {
        Database(dataSource)
    } catch (failure: Throwable) {
        dataSource.close()
        throw failure
    }
    val users = ExposedUserRepository()
    val hasher = PasswordHasher()
    val tokens = TokenService(config.auth)
    val sessionRepository = ExposedSessionRepository()
    val authService = AuthService(db, users, sessionRepository, hasher, tokens)
    val hotelRepository = ExposedHotelRepository()
    val hotelService = HotelService(db, hotelRepository, users, hasher)
    val notificationRepository = ExposedNotificationRepository()
    val orderRepository = ExposedOrderRepository()
    val orderService = OrderService(db, orderRepository, hotelRepository, notificationRepository)
    val adminService = AdminService(db, ExposedAdminRepository(), orderRepository, sessionRepository)
    val notificationService = NotificationService(db, notificationRepository)
    AccountSeeder(db, users, hasher, config.auth).run()

    val retention = RetentionService(db, sessionRepository, notificationRepository)
    val retentionJob = launch {
        while (true) {
            runCatching { retention.runOnce() }
                .onFailure { log.warn("Retention cleanup failed: ${it.javaClass.simpleName}") }
            delay(Duration.ofHours(6).toMillis())
        }
    }
    monitor.subscribe(ApplicationStopping) { retentionJob.cancel() }

    // Browser clients (admin web) run on another origin. Production must list origins explicitly.
    install(CORS) {
        allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post); allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch); allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.ContentType)
        allowHeader("Idempotency-Key")
        if (!config.isProduction) {
            allowOrigins { it.startsWith("http://localhost:") || it.startsWith("http://127.0.0.1:") }
        }
        config.corsAllowedOrigins.forEach { origin ->
            val (scheme, host) = origin.split("://", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            allowHost(host, schemes = listOf(scheme))
        }
    }
    configureSerialization()
    configureErrorHandling()
    configureAuthentication(authService, tokens, config.auth.loginRateLimitPerMinute)

    routing {
        authRoutes(authService)
        hotelRoutes(hotelService)
        orderRoutes(orderService)
        notificationRoutes(notificationService)
        adminRoutes(adminService)
        // API docs are not exposed in production unless explicitly enabled.
        if (!config.isProduction || System.getenv("ENABLE_API_DOCS") == "true") {
            swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        }
        get("/health") {
            call.respond(HealthResponse("UP"))
        }
    }
}