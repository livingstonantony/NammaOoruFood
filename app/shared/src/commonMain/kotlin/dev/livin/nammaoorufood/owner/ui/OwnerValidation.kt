package dev.livin.nammaoorufood.owner.ui

import dev.livin.nammaoorufood.network.OperatingHourDto

private val PRICE = Regex("^\\d{1,6}(\\.\\d{1,2})?$")
private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

/** Returns an error message, or null when valid. The server re-validates everything. */
fun validatePrice(price: String): String? = when {
    !PRICE.matches(price.trim()) -> "Enter a price like 60 or 60.50"
    price.trim().toDouble() <= 0.0 -> "Price must be greater than zero"
    else -> null
}

fun validateCategoryName(name: String): String? = when {
    name.isBlank() -> "Enter the category name"
    name.trim().length > 60 -> "Category name is too long"
    else -> null
}

fun validateItemName(name: String): String? = when {
    name.isBlank() -> "Enter the item name"
    name.trim().length > 100 -> "Name is too long"
    else -> null
}

data class HoursRow(val dayOfWeek: Int, val opens: String = "", val closes: String = "")

sealed interface HoursResult {
    data class Valid(val hours: List<OperatingHourDto>) : HoursResult
    data class Invalid(val message: String) : HoursResult
}

val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** A row with both times blank means the hotel is closed that day. */
fun buildHours(rows: List<HoursRow>): HoursResult {
    val out = mutableListOf<OperatingHourDto>()
    for (row in rows) {
        val o = row.opens.trim()
        val c = row.closes.trim()
        val day = DAY_NAMES[row.dayOfWeek - 1]
        if (o.isEmpty() && c.isEmpty()) continue
        if (o.isEmpty() || c.isEmpty()) return HoursResult.Invalid("$day: enter both opening and closing time")
        if (!TIME.matches(o) || !TIME.matches(c)) return HoursResult.Invalid("$day: use 24-hour time like 18:00")
        if (o == c) return HoursResult.Invalid("$day: opening and closing time must differ")
        out += OperatingHourDto(row.dayOfWeek, o, c)
    }
    return HoursResult.Valid(out)
}

fun hoursRowsOf(hours: List<OperatingHourDto>): List<HoursRow> = (1..7).map { d ->
    hours.firstOrNull { it.dayOfWeek == d }?.let { HoursRow(d, it.opensAt.take(5), it.closesAt.take(5)) } ?: HoursRow(d)
}

fun validateHotelForm(name: String, address: String): String? = when {
    name.isBlank() -> "Enter the hotel name"
    address.isBlank() -> "Enter the hotel address"
    else -> null
}
