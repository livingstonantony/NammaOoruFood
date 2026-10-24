package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.network.Role

/** Which product this build is. Each variant only accepts accounts of its own role; the server still enforces roles. */
enum class AppVariant(val role: Role, val title: String) {
    CUSTOMER(Role.CUSTOMER, "Namma Ooru Food"),
    OWNER(Role.HOTEL_OWNER, "Namma Ooru Food — Hotel Owner"),
    ADMIN(Role.ADMIN, "Namma Ooru Food — Admin");

    companion object {
        fun parse(value: String?): AppVariant? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
