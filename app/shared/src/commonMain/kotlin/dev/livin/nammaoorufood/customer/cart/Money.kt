package dev.livin.nammaoorufood.customer.cart

/** Prices are handled in paise to avoid floating point; the server remains the authority on totals. */
object Money {
    fun parsePaise(value: String): Long {
        val parts = value.trim().split('.')
        val whole = parts[0].toLongOrNull() ?: 0L
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
        return whole * 100 + fraction
    }

    fun format(paise: Long): String = "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
}
