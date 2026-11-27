package dev.livin.nammaoorufood.miniapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class MockCustomerClientTest {
    @Test
    fun mockModeRequiresExplicitLocalOptIn() {
        assertTrue(isMockMode("localhost", "?mock=true"))
        assertTrue(isMockMode("127.0.0.1", "?other=1&mock=true"))
        assertTrue(isMockMode("pulsate-shaded-crust.ngrok-free.dev", "?mock=true"))
        assertFalse(isMockMode("pulsate-shaded-crust.ngrok-free.dev", ""))
        assertFalse(isMockMode("other.ngrok-free.dev", "?mock=true"))
        assertFalse(isMockMode("example.com", "?mock=true"))
        assertFalse(isMockMode("localhost", ""))
        assertFalse(isMockMode("localhost", "?mock=false"))
    }

    @Test
    fun mockOrdersCanBePlacedRetriedAndCancelled(): Promise<Unit> = MainScope().promise {
        val client = MockCustomerClient()
        val hotel = client.hotels(1).items.first()
        val item = client.menu(hotel.id).items.first()
        val request = PlaceOrder(hotel.id, listOf(OrderLine(item.id, 2)))
        val order = client.place(request, "key")

        assertEquals("80.00", order.total)
        assertEquals("PLACED", order.status)
        assertEquals(order, client.place(request, "key"))
        assertEquals(1, client.orders().items.size)
        assertEquals("CANCELLED", client.cancel(order.id).status)
        assertEquals("CANCELLED", client.orders().items.single().status)
        try {
            client.cancel(order.id)
            fail("A cancelled order cannot be cancelled again")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
        }
        assertTrue(MockCustomerClient().orders().items.isEmpty())
    }
}
