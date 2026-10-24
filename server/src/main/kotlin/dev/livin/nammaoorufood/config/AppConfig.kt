package dev.livin.nammaoorufood.config

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
) {
    // Fail closed: anything other than an explicit local/test environment gets production rules.
    val isProduction: Boolean get() = !environment.trim().lowercase().let { it == "local" || it == "test" }

    companion object {
        // Defaults target local development only; production must supply every secret/credential variable.
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val environment = env["APP_ENV"]?.takeIf { it.isNotBlank() }
                ?: error("APP_ENV must be set explicitly (local, test or production)")
            val production = environment.trim().lowercase().let { it != "local" && it != "test" }
            fun required(key: String, devDefault: String): String =
                env[key] ?: if (production) error("Missing required environment variable $key") else devDefault
            val jwtSecret = required("JWT_SECRET", "dev-only-secret-change-me-dev-only-secret-change-me")
            check(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
            val poolSize = env["DB_POOL_SIZE"]?.let {
                it.toIntOrNull() ?: error("DB_POOL_SIZE must be an integer")
            } ?: 10
            require(poolSize in 1..100) { "DB_POOL_SIZE must be between 1 and 100" }
            return AppConfig(
                environment = environment,
                database = DatabaseConfig(
                    url = required("DB_URL", "jdbc:mysql://localhost:3306/nammaoorufood?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"),
                    user = env["DB_USER"]?.takeIf { it.isNotBlank() }
                        ?: error("Missing required environment variable DB_USER"),
                    password = env["DB_PASSWORD"]?.takeIf { it.isNotBlank() }
                        ?: error("Missing required environment variable DB_PASSWORD"),
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
            )
        }
    }
}
