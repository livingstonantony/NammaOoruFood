package dev.livin.nammaoorufood.user

import dev.livin.nammaoorufood.database.UsersTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.sql.SQLIntegrityConstraintViolationException

interface UserRepository {
    fun findByUsername(username: String): User?
    fun findById(id: Long): User?
    fun create(username: String, passwordHash: String, role: Role, displayName: String, phone: String?): User
    fun existsWithRole(role: Role): Boolean
}

class ExposedUserRepository : UserRepository {
    override fun findByUsername(username: String): User? =
        UsersTable.selectAll().where { UsersTable.username eq username }.limit(1).singleOrNull()?.toUser()

    override fun findById(id: Long): User? =
        UsersTable.selectAll().where { UsersTable.id eq id }.limit(1).singleOrNull()?.toUser()

    override fun create(username: String, passwordHash: String, role: Role, displayName: String, phone: String?): User {
        val id = try {
            UsersTable.insert {
                it[UsersTable.username] = username
                it[UsersTable.passwordHash] = passwordHash
                it[UsersTable.role] = role
                it[UsersTable.displayName] = displayName
                it[UsersTable.phone] = phone
            }[UsersTable.id]
        } catch (failure: ExposedSQLException) {
            val integrityFailure = generateSequence(failure.cause) { it.cause }
                .filterIsInstance<SQLIntegrityConstraintViolationException>()
                .firstOrNull()
            if (integrityFailure != null) throw integrityFailure
            throw failure
        }
        return User(id, username, passwordHash, role, displayName, active = true)
    }

    override fun existsWithRole(role: Role): Boolean =
        UsersTable.selectAll().where { UsersTable.role eq role }.limit(1).singleOrNull() != null

    private fun org.jetbrains.exposed.sql.ResultRow.toUser() = User(
        id = this[UsersTable.id],
        username = this[UsersTable.username],
        passwordHash = this[UsersTable.passwordHash],
        role = this[UsersTable.role],
        displayName = this[UsersTable.displayName],
        active = this[UsersTable.active],
    )
}
