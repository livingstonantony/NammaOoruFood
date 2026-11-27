package dev.livin.nammaoorufood.config

import java.nio.file.Files
import java.nio.file.Path
import java.net.URI

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
)

data class AuthConfig(
    val jwtSecret: String,
    val accessTokenMinutes: Long,
    val refreshTokenDays: Long,
    val loginRateLimitPerMinute: Int,
    val seedDevAccounts: Boolean,
    val bootstrapAdminUsername: String?,
    val bootstrapAdminPassword: String?,
)

data class AppConfig(
    val environment: String,
    val database: DatabaseConfig,
    val auth: AuthConfig,
    val corsAllowedOrigins: List<String> = emptyList(),
    val telegramBotToken: String? = null,
    val telegramBotPollingEnabled: Boolean = environment.trim().lowercase() != "test",
    val telegramMiniAppUrl: String? = null,
) {
    // Fail closed: anything other than an explicit local/test environment gets production rules.
    val isProduction: Boolean get() = !environment.trim().lowercase().let { it == "local" || it == "test" }

    companion object {
        fun fromSystemEnvironment(env: Map<String, String> = System.getenv(), file: Path = Path.of(".env")): AppConfig {
            if (env.containsKey("TELEGRAM_BOT_TOKEN")) return fromEnv(env)
            if (!Files.exists(file)) return fromEnv(env)
            val entries = Files.lines(file).use { lines ->
                lines.map { line ->
                    Regex("""^\s*(?:export\s+)?TELEGRAM_BOT_TOKEN\s*=\s*(.*?)\s*$""").matchEntire(line)?.groupValues?.get(1)
                }.filter { it != null }.toList()
            }
            require(entries.size <= 1) { "TELEGRAM_BOT_TOKEN appears more than once in .env" }
            val value = entries.singleOrNull() ?: return fromEnv(env)
            val token = if (value.startsWith('"') && value.endsWith('"') && value.length >= 2 ||
                value.startsWith('\'') && value.endsWith('\'') && value.length >= 2
            ) value.substring(1, value.length - 1) else value
            require(token.isEmpty() || token.matches(Regex("""\d+:[A-Za-z0-9_-]+"""))) {
                "Invalid TELEGRAM_BOT_TOKEN in .env"
            }
            return fromEnv(env + ("TELEGRAM_BOT_TOKEN" to token))
        }

        // Defaults target local development only; production must supply every secret/credential variable.
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val environment = env["APP_ENV"]?.takeIf { it.isNotBlank() }
                ?: error("APP_ENV must be set explicitly (local, test or production)")
            val production = environment.trim().lowercase().let { it != "local" && it != "test" }
            fun required(key: String, devDefault: String): String =
                env[key] ?: if (production) error("Missing required environment variable $key") else devDefault
            val jwtSecret = required("JWT_SECRET", "dev-only-secret-change-me-dev-only-secret-change-me")
            check(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
            val telegramToken = env["TELEGRAM_BOT_TOKEN"]?.takeIf { it.isNotBlank() }
            require(telegramToken == null || telegramToken.matches(Regex("""\d+:[A-Za-z0-9_-]+"""))) {
                "Invalid TELEGRAM_BOT_TOKEN"
            }
            val miniAppUrl = env["TELEGRAM_MINI_APP_URL"]?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                val url = try {
                    URI(value)
                } catch (_: java.net.URISyntaxException) {
                    throw IllegalArgumentException("Invalid TELEGRAM_MINI_APP_URL")
                }
                require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null &&
                    (url.path.isNullOrEmpty() || url.path == "/") &&
                    (url.rawQuery == null || (!production && url.rawQuery == "mock=true")) && url.rawFragment == null) {
                    "TELEGRAM_MINI_APP_URL must be an HTTPS origin; ?mock=true is allowed only in local/test"
                }
                value
            }
            val poolSize = env["DB_POOL_SIZE"]?.let {
                it.toIntOrNull() ?: error("DB_POOL_SIZE must be an integer")
            } ?: 10
            require(poolSize in 1..100) { "DB_POOL_SIZE must be between 1 and 100" }
            return AppConfig(
                environment = environment,
                database = DatabaseConfig(
                    url = required("DB_URL", "jdbc:mysql://localhost:3306/nammaoorufood?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"),
                    user = required("DB_USER", "root"),
                    password = required("DB_PASSWORD", "root"),
                    maxPoolSize = poolSize,
                ),
                auth = AuthConfig(
                    jwtSecret = jwtSecret,
                    accessTokenMinutes = env["ACCESS_TOKEN_MINUTES"]?.toLongOrNull() ?: 15,
                    refreshTokenDays = env["REFRESH_TOKEN_DAYS"]?.toLongOrNull() ?: 30,
                    loginRateLimitPerMinute = env["LOGIN_RATE_LIMIT_PER_MINUTE"]?.toIntOrNull() ?: 10,
                    seedDevAccounts = !production && env["SEED_DEV_ACCOUNTS"] != "false",
                    bootstrapAdminUsername = env["BOOTSTRAP_ADMIN_USERNAME"],
                    bootstrapAdminPassword = env["BOOTSTRAP_ADMIN_PASSWORD"],
                ),
                corsAllowedOrigins = env["CORS_ALLOWED_ORIGINS"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
                telegramBotToken = telegramToken,
                telegramBotPollingEnabled = env["TELEGRAM_BOT_POLLING_ENABLED"]?.let {
                    it.toBooleanStrictOrNull() ?: error("TELEGRAM_BOT_POLLING_ENABLED must be true or false")
                } ?: (environment.trim().lowercase() != "test"),
                telegramMiniAppUrl = miniAppUrl,
            )
        }
    }
}
