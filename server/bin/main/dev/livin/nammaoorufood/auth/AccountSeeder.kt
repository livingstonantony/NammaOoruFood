package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.config.AuthConfig
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.user.Role
import dev.livin.nammaoorufood.user.UserRepository
import org.slf4j.LoggerFactory

// Creates accounts that must exist for the system to be usable. Never logs credentials.
class AccountSeeder(
    private val db: Database,
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val config: AuthConfig,
) {
    private val log = LoggerFactory.getLogger(AccountSeeder::class.java)

    fun run() {
        val adminUser = config.bootstrapAdminUsername
        val adminPassword = config.bootstrapAdminPassword
        if (adminUser != null && adminPassword != null) {
            seedIfMissing(adminUser, adminPassword, Role.ADMIN, "Administrator")
        }
        if (config.seedDevAccounts) {
            log.warn("Seeding development accounts (local/test environments only)")
            seedIfMissing("admin", "admin12345", Role.ADMIN, "Dev Admin")
            seedIfMissing("owner", "owner12345", Role.HOTEL_OWNER, "Dev Owner")
            seedIfMissing("customer", "customer12345", Role.CUSTOMER, "Dev Customer")
        }
    }

    private fun seedIfMissing(username: String, password: String, role: Role, displayName: String) {
        db.transaction { conn ->
            if (users.findByUsername(conn, username) == null) {
                users.create(conn, username, hasher.hash(password), role, displayName, null)
            }
        }
    }
}
