package dev.livin.nammaoorufood.user

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

interface UserRepository {
    fun findByUsername(conn: Connection, username: String): User?
    fun findById(conn: Connection, id: Long): User?
    fun create(conn: Connection, username: String, passwordHash: String, role: Role, displayName: String, phone: String?): User
    fun existsWithRole(conn: Connection, role: Role): Boolean
}

class JdbcUserRepository : UserRepository {
    override fun findByUsername(conn: Connection, username: String): User? =
        conn.prepareStatement("SELECT id, username, password_hash, role, display_name, active FROM users WHERE username = ?").use {
            it.setString(1, username)
            it.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }

    override fun findById(conn: Connection, id: Long): User? =
        conn.prepareStatement("SELECT id, username, password_hash, role, display_name, active FROM users WHERE id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }

    override fun create(conn: Connection, username: String, passwordHash: String, role: Role, displayName: String, phone: String?): User {
        val id = conn.prepareStatement(
            "INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, username)
            it.setString(2, passwordHash)
            it.setString(3, role.name)
            it.setString(4, displayName)
            it.setString(5, phone)
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
        return User(id, username, passwordHash, role, displayName, active = true)
    }

    override fun existsWithRole(conn: Connection, role: Role): Boolean =
        conn.prepareStatement("SELECT 1 FROM users WHERE role = ? LIMIT 1").use {
            it.setString(1, role.name)
            it.executeQuery().use { rs -> rs.next() }
        }

    private fun ResultSet.toUser() = User(
        id = getLong("id"),
        username = getString("username"),
        passwordHash = getString("password_hash"),
        role = Role.valueOf(getString("role")),
        displayName = getString("display_name"),
        active = getBoolean("active"),
    )
}
