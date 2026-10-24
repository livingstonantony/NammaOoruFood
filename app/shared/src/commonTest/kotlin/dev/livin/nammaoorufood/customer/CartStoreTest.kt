package dev.livin.nammaoorufood.customer

import dev.livin.nammaoorufood.customer.cart.AddResult
import dev.livin.nammaoorufood.customer.cart.CartStore
import dev.livin.nammaoorufood.customer.cart.Money
import kotlin.test.*

class CartStoreTest {
    private var n = 0
    private val cart = CartStore { "key${n++}" }

    @Test fun moneyParsingAndFormatting() {
        assertEquals(1500, Money.parsePaise("15.00"))
        assertEquals(1550, Money.parsePaise("15.5"))
        assertEquals(1500, Money.parsePaise("15"))
        assertEquals("₹15.05", Money.format(1505))
        assertEquals("₹0.00", Money.format(0))
    }

    @Test fun addMergesDuplicatesAndTotals() {
        assertEquals(AddResult.ADDED, cart.add(1, "H1", food(1, "15.00")))
        cart.add(1, "H1", food(1, "15.00"))
        cart.add(1, "H1", food(2, "80.50"))
        val s = cart.state.value
        assertEquals(2, s.lines.size)
        assertEquals(3, s.itemCount)
        assertEquals(1500 * 2 + 8050, s.totalPaise)
        assertEquals(2, s.quantityOf(1))
    }

    @Test fun cannotMixHotelsWithoutReplacing() {
        cart.add(1, "H1", food(1))
        assertEquals(AddResult.HOTEL_CONFLICT, cart.add(2, "H2", food(5)))
        assertEquals(1L, cart.state.value.hotelId)
        assertEquals(AddResult.ADDED, cart.replaceWith(2, "H2", food(5)))
        assertEquals(2L, cart.state.value.hotelId)
        assertEquals(1, cart.state.value.lines.size)
    }

    @Test fun unavailableItemsAreRejected() {
        assertEquals(AddResult.UNAVAILABLE, cart.add(1, "H1", food(1, available = false)))
        assertTrue(cart.state.value.isEmpty)
    }

    @Test fun quantityAndLineLimitsAreEnforced() {
        repeat(CartStore.MAX_QUANTITY) { cart.add(1, "H1", food(1)) }
        assertEquals(AddResult.LIMIT_REACHED, cart.add(1, "H1", food(1)))
        cart.increment(1)
        assertEquals(CartStore.MAX_QUANTITY, cart.state.value.quantityOf(1))
        val c2 = CartStore()
        (1L..CartStore.MAX_LINES).forEach { c2.add(1, "H1", food(it)) }
        assertEquals(AddResult.LIMIT_REACHED, c2.add(1, "H1", food(1000)))
    }

    @Test fun decrementRemovesLineAndEmptyCartForgetsHotel() {
        cart.add(1, "H1", food(1))
        cart.add(1, "H1", food(1))
        cart.decrement(1)
        assertEquals(1, cart.state.value.quantityOf(1))
        cart.decrement(1)
        assertTrue(cart.state.value.isEmpty)
        assertNull(cart.state.value.hotelId)
        assertEquals(AddResult.ADDED, cart.add(2, "H2", food(5)))
    }

    @Test fun checkoutKeyChangesWithEveryEdit() {
        val keys = mutableSetOf(cart.state.value.checkoutKey)
        cart.add(1, "H1", food(1)); keys += cart.state.value.checkoutKey
        cart.increment(1); keys += cart.state.value.checkoutKey
        cart.remove(1); keys += cart.state.value.checkoutKey
        assertEquals(4, keys.size)
    }

    @Test fun removingUnknownItemKeepsKey() {
        cart.add(1, "H1", food(1))
        val key = cart.state.value.checkoutKey
        cart.remove(77)
        assertEquals(key, cart.state.value.checkoutKey)
    }
}
