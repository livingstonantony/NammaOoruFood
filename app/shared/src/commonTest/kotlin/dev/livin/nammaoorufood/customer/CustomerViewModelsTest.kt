package dev.livin.nammaoorufood.customer

import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.cart.CartStore
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.ui.*
import dev.livin.nammaoorufood.network.NotificationDto
import dev.livin.nammaoorufood.network.NotificationPage
import dev.livin.nammaoorufood.network.OrderPage
import dev.livin.nammaoorufood.network.OrderStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerViewModelsTest {
    private val shop = FakeShop()
    private val cart = CartStore()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun hotelListLoadsAndSearchesWithFilters() = runTest {
        val vm = HotelListViewModel(shop)
        assertEquals(2, (vm.state.value.hotels as Load.Success).data.size)
        vm.onIntent(HotelListIntent.QueryChanged("amma"))
        vm.onIntent(HotelListIntent.ToggleOpenOnly)
        assertEquals("amma" to true, shop.hotelQueries.last())
    }

    @Test fun hotelListShowsErrorOnNetworkFailure() = runTest {
        shop.hotels = ApiResult.NetworkError("x")
        val vm = HotelListViewModel(shop)
        assertTrue((vm.state.value.hotels as Load.Error).message.contains("Cannot reach"))
    }

    @Test fun menuLoadsAndCartInteractionsFlowThrough() = runTest {
        val vm = MenuViewModel(1, shop, cart)
        assertTrue(vm.state.value.content is Load.Success)
        vm.onIntent(MenuIntent.Add(food(1)))
        vm.onIntent(MenuIntent.Increment(1))
        assertEquals(2, vm.state.value.cart.quantityOf(1))
        vm.onIntent(MenuIntent.Decrement(1))
        assertEquals(1, vm.state.value.cart.quantityOf(1))
    }

    @Test fun menuAsksBeforeSwitchingHotels() = runTest {
        cart.add(2, "Other", food(9))
        val vm = MenuViewModel(1, shop, cart)
        vm.onIntent(MenuIntent.Add(food(1)))
        assertEquals(food(1), vm.state.value.pendingConflict)
        assertEquals(2L, cart.state.value.hotelId)
        vm.onIntent(MenuIntent.ConfirmReplaceCart)
        assertNull(vm.state.value.pendingConflict)
        assertEquals(1L, cart.state.value.hotelId)
        assertEquals(1, cart.state.value.lines.size)
    }

    @Test fun menuRefusesUnavailableItems() = runTest {
        val vm = MenuViewModel(1, shop, cart)
        vm.onIntent(MenuIntent.Add(food(3, available = false)))
        assertNotNull(vm.state.value.message)
        assertTrue(cart.state.value.isEmpty)
    }

    @Test fun placingOrderSendsNoPricesClearsCartAndExposesOrderId() = runTest {
        cart.add(1, "Hotel1", food(1)); cart.add(1, "Hotel1", food(1)); cart.add(1, "Hotel1", food(2))
        val key = cart.state.value.checkoutKey
        val vm = CartViewModel(shop, cart)
        vm.onIntent(CartIntent.NoteChanged("  less spicy "))
        vm.onIntent(CartIntent.PlaceOrder)
        val (req, sentKey) = shop.placed.single()
        assertEquals(key, sentKey)
        assertEquals(1L, req.hotelId)
        assertEquals(listOf(1L to 2, 2L to 1), req.items.map { it.foodItemId to it.quantity })
        assertEquals("less spicy", req.note)
        assertEquals(99L, vm.state.value.placedOrderId)
        assertTrue(cart.state.value.isEmpty)
        vm.onIntent(CartIntent.OrderShown)
        assertNull(vm.state.value.placedOrderId)
    }

    @Test fun failedOrderKeepsCartShowsServerErrorAndRetriesWithSameKey() = runTest {
        cart.add(1, "Hotel1", food(1))
        shop.place = ApiResult.Rejected(409, "Hotel is closed", false)
        val vm = CartViewModel(shop, cart)
        vm.onIntent(CartIntent.PlaceOrder)
        assertEquals("Hotel is closed", vm.state.value.error)
        assertFalse(cart.state.value.isEmpty)
        vm.onIntent(CartIntent.PlaceOrder)
        assertEquals(2, shop.placed.size)
        assertEquals(shop.placed[0].second, shop.placed[1].second)
    }

    @Test fun editingCartAfterFailureUsesFreshKeyAndClearsError() = runTest {
        cart.add(1, "Hotel1", food(1))
        shop.place = ApiResult.NetworkError("x")
        val vm = CartViewModel(shop, cart)
        vm.onIntent(CartIntent.PlaceOrder)
        assertNotNull(vm.state.value.error)
        vm.onIntent(CartIntent.Increment(1))
        assertNull(vm.state.value.error)
        vm.onIntent(CartIntent.PlaceOrder)
        assertNotEquals(shop.placed[0].second, shop.placed[1].second)
    }

    @Test fun emptyCartCannotBePlaced() = runTest {
        CartViewModel(shop, cart).onIntent(CartIntent.PlaceOrder)
        assertTrue(shop.placed.isEmpty())
    }

    @Test fun ordersSplitActiveFromPast() = runTest {
        shop.active = ApiResult.Success(listOf(anOrder(1, OrderStatus.ACCEPTED, 4)))
        shop.history = ApiResult.Success(OrderPage(listOf(anOrder(1), anOrder(2, OrderStatus.COMPLETED)), 1, 50, 2))
        val vm = OrdersViewModel(shop)
        assertEquals(listOf(1L), vm.state.value.active.map { it.id })
        assertEquals(listOf(2L), vm.state.value.past.map { it.id })
    }

    @Test fun ordersRefreshFailureKeepsExistingData() = runTest {
        shop.active = ApiResult.Success(listOf(anOrder(1)))
        val vm = OrdersViewModel(shop)
        shop.active = ApiResult.NetworkError("x")
        vm.refresh()
        assertEquals(1, vm.state.value.active.size)
        assertTrue(vm.state.value.load is Load.Success)
    }

    @Test fun orderDetailRefreshTracksStatusChanges() = runTest {
        shop.single = ApiResult.Success(anOrder(1, OrderStatus.ACCEPTED, 7))
        val vm = OrderDetailViewModel(1, shop)
        assertEquals(7, (vm.state.value.order as Load.Success).data.pickupToken)
        shop.single = ApiResult.Success(anOrder(1, OrderStatus.READY_FOR_PICKUP, 7))
        vm.refresh()
        assertEquals(OrderStatus.READY_FOR_PICKUP, (vm.state.value.order as Load.Success).data.status)
        shop.single = ApiResult.NetworkError("x")
        vm.refresh()
        assertTrue(vm.state.value.order is Load.Success)
    }

    @Test fun cancelUpdatesOrderAndFailureSurfacesError() = runTest {
        val vm = OrderDetailViewModel(1, shop)
        vm.cancel()
        assertEquals(OrderStatus.CANCELLED, (vm.state.value.order as Load.Success).data.status)
        assertFalse(vm.state.value.cancelling)
        shop.cancel = ApiResult.Rejected(409, "Order can no longer be cancelled", false)
        vm.cancel()
        assertEquals("Order can no longer be cancelled", vm.state.value.actionError)
        assertEquals(2, shop.cancelCalls)
    }

    @Test fun notificationsLoadAndDismissMarksRead() = runTest {
        shop.notifications = ApiResult.Success(NotificationPage(listOf(NotificationDto(4, 9, "ORDER_READY", "Ready", "msg", false, "t")), 1))
        val vm = NotificationsViewModel(shop)
        vm.refresh()
        assertEquals(1, vm.state.value.unread.size)
        vm.dismiss(4)
        assertTrue(vm.state.value.unread.isEmpty())
        assertEquals(listOf(4L), shop.read)
    }

    @Test fun duplicatePlaceWhileInFlightIsIgnored() = runTest {
        cart.add(1, "Hotel1", food(1))
        val gate = CompletableDeferred<Unit>()
        val slow = object : dev.livin.nammaoorufood.customer.data.ShopRepository by shop {
            override suspend fun placeOrder(request: dev.livin.nammaoorufood.network.PlaceOrderRequest, idempotencyKey: String):
                ApiResult<dev.livin.nammaoorufood.network.OrderDto> { shop.placed += request to idempotencyKey; gate.await(); return shop.place }
        }
        val vm = CartViewModel(slow, cart)
        vm.onIntent(CartIntent.PlaceOrder)
        vm.onIntent(CartIntent.PlaceOrder)
        assertTrue(vm.state.value.placing)
        gate.complete(Unit)
        assertEquals(1, shop.placed.size)
    }
}
