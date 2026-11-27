package dev.livin.nammaoorufood.miniapp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CartTest {
    @Test
    fun estimatesInPaiseWithoutFloatingPointRounding() {
        val items = listOf(FoodItem(1, "Tea", price = "0.10"), FoodItem(2, "Dosa", price = "25.50"))
        assertEquals("51.30", estimatedTotal(items, mapOf(1L to 3, 2L to 2)))
        assertEquals("0.00", estimatedTotal(items, emptyMap()))
    }

    @Test
    fun pendingOrderPreservesKeyAndLinesAcrossSerialization() {
        val order = PendingOrder(
            1, Hotel(2, "Hotel", address = "Street", isOpen = true),
            listOf(FoodItem(3, "Dosa", price = "25.00")),
            PlaceOrder(2, listOf(OrderLine(3, 2)), "Less spicy"), "retry-key-123456",
        )
        assertEquals(order, Json.decodeFromString<PendingOrder>(Json.encodeToString(order)))
    }
}
