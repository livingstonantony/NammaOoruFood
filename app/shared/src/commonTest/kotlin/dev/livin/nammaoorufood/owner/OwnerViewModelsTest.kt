package dev.livin.nammaoorufood.owner

import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.anOrder
import dev.livin.nammaoorufood.customer.aHotel
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.food
import dev.livin.nammaoorufood.network.*
import dev.livin.nammaoorufood.owner.data.OwnerRepository
import dev.livin.nammaoorufood.owner.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

class FakeOwner : OwnerRepository {
    var hotels: ApiResult<List<HotelDto>> = ApiResult.Success(listOf(aHotel(1)))
    var orders: ApiResult<OrderPage> = ApiResult.Success(OrderPage(listOf(anOrder(5)), 1, 50, 1))
    var action: ApiResult<OrderDto> = ApiResult.Success(anOrder(5, OrderStatus.ACCEPTED, 1))
    var menu: ApiResult<MenuResponse> = ApiResult.Success(MenuResponse(emptyList(), listOf(food(1), food(2))))
    var itemResult: ApiResult<FoodItemDto> = ApiResult.Success(food(1))
    val calls = mutableListOf<String>()
    val requestedStatuses = mutableListOf<Set<OrderStatus>>()
    var hoursSent: List<OperatingHourDto>? = null

    override suspend fun hotels() = hotels
    override suspend fun createHotel(request: HotelRequest): ApiResult<HotelDto> { calls += "create"; return ApiResult.Success(aHotel(7)) }
    override suspend fun updateHotel(id: Long, request: HotelRequest): ApiResult<HotelDto> { calls += "update"; return ApiResult.Success(aHotel(id)) }
    override suspend fun setHours(id: Long, hours: List<OperatingHourDto>): ApiResult<HotelDto> { hoursSent = hours; return ApiResult.Success(aHotel(id)) }
    override suspend fun setAcceptingOrders(id: Long, accepting: Boolean): ApiResult<HotelDto> {
        calls += "accepting=$accepting"
        return ApiResult.Success(aHotel(id).copy(acceptingOrders = accepting))
    }
    override suspend fun dashboard() = ApiResult.Success(OwnerDashboardDto("100.00", 1, 0, 2, 3, 4, 5))
    override suspend fun orders(hotelId: Long, statuses: Set<OrderStatus>): ApiResult<OrderPage> { requestedStatuses += statuses; return orders }
    override suspend fun accept(orderId: Long): ApiResult<OrderDto> { calls += "accept$orderId"; return action }
    override suspend fun reject(orderId: Long): ApiResult<OrderDto> { calls += "reject$orderId"; return action }
    override suspend fun updateStatus(orderId: Long, status: OrderStatus): ApiResult<OrderDto> { calls += "status$orderId=$status"; return action }
    override suspend fun menu(hotelId: Long) = menu
    val categories = mutableListOf<String>()
    override suspend fun createCategory(hotelId: Long, name: String): ApiResult<CategoryDto> { categories += name; return ApiResult.Success(CategoryDto(7, name, 0)) }
    override suspend fun createItem(hotelId: Long, request: FoodItemRequest): ApiResult<FoodItemDto> { calls += "createItem"; return itemResult }
    override suspend fun updateItem(hotelId: Long, itemId: Long, request: FoodItemRequest): ApiResult<FoodItemDto> { calls += "updateItem"; return itemResult }
    override suspend fun setItemAvailability(hotelId: Long, itemId: Long, available: Boolean): ApiResult<FoodItemDto> {
        calls += "avail$itemId=$available"
        return ApiResult.Success(food(itemId, available = available))
    }
    override suspend fun deleteItem(hotelId: Long, itemId: Long): ApiResult<Unit> { calls += "delete$itemId"; return ApiResult.Success(Unit) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerViewModelsTest {
    private val repo = FakeOwner()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun addCategoryValidatesThenCreatesAndReportsId() = runTest {
        val vm = OwnerMenuViewModel(1, repo)
        vm.addCategory("  ")
        assertTrue(repo.categories.isEmpty())
        assertNotNull(vm.state.value.message)
        var id: Long? = null
        vm.addCategory(" Rice ") { id = it }
        assertEquals(listOf("Rice"), repo.categories)
        assertEquals(7L, id)
    }

    @Test fun priceValidation() {
        assertNull(validatePrice("60"))
        assertNull(validatePrice("60.50"))
        assertNotNull(validatePrice("abc"))
        assertNotNull(validatePrice("0"))
        assertNotNull(validatePrice("-5"))
        assertNotNull(validatePrice("1.234"))
    }

    @Test fun hoursBuildSkipsClosedDaysAndRejectsBadInput() {
        val rows = hoursRowsOf(emptyList()).toMutableList()
        rows[0] = HoursRow(1, "18:00", "23:00")
        assertEquals(listOf(OperatingHourDto(1, "18:00", "23:00")), (buildHours(rows) as HoursResult.Valid).hours)
        rows[1] = HoursRow(2, "18:00", "")
        assertTrue(buildHours(rows) is HoursResult.Invalid)
        rows[1] = HoursRow(2, "25:00", "23:00")
        assertTrue(buildHours(rows) is HoursResult.Invalid)
        rows[1] = HoursRow(2, "18:00", "18:00")
        assertTrue(buildHours(rows) is HoursResult.Invalid)
    }

    @Test fun nextStepFollowsLifecycle() {
        assertNull(OrderStatus.PLACED.nextStep())
        assertEquals(OrderStatus.PREPARING, OrderStatus.ACCEPTED.nextStep()?.first)
        assertEquals(OrderStatus.READY_FOR_PICKUP, OrderStatus.PREPARING.nextStep()?.first)
        assertEquals(OrderStatus.COMPLETED, OrderStatus.READY_FOR_PICKUP.nextStep()?.first)
        assertNull(OrderStatus.REJECTED.nextStep())
    }

    @Test fun hotelCreationRequiresNameAndAddress() = runTest {
        repo.hotels = ApiResult.Success(emptyList())
        val vm = OwnerHotelViewModel(repo)
        assertNull(vm.state.value.hotel)
        vm.createHotel("", "", "addr", "")
        assertTrue(repo.calls.isEmpty())
        assertNotNull(vm.state.value.message)
        vm.createHotel("Amma", "", "Main St", "")
        assertEquals(listOf("create"), repo.calls)
        assertEquals(7, vm.state.value.hotel?.id)
    }

    @Test fun acceptingSwitchUpdatesHotel() = runTest {
        val vm = OwnerHotelViewModel(repo)
        vm.setAccepting(1, false)
        assertFalse(vm.state.value.hotel!!.acceptingOrders)
    }

    @Test fun invalidHoursAreNotSent() = runTest {
        val vm = OwnerHotelViewModel(repo)
        vm.setHours(1, listOf(HoursRow(1, "18:00", "")))
        assertNull(repo.hoursSent)
        vm.setHours(1, listOf(HoursRow(1, "18:00", "23:00")))
        assertEquals(1, repo.hoursSent?.size)
    }

    @Test fun ordersBoardLoadsPerTabAndActs() = runTest {
        val vm = OwnerOrdersViewModel(1, repo)
        assertEquals(setOf(OrderStatus.PLACED), repo.requestedStatuses.last())
        vm.onIntent(OwnerOrdersIntent.Accept(5))
        assertEquals(listOf("accept5"), repo.calls)
        vm.onIntent(OwnerOrdersIntent.SelectTab(OrderTab.PREPARING))
        assertEquals(setOf(OrderStatus.ACCEPTED, OrderStatus.PREPARING), repo.requestedStatuses.last())
        vm.onIntent(OwnerOrdersIntent.Advance(anOrder(5, OrderStatus.PREPARING, 1)))
        assertEquals("status5=READY_FOR_PICKUP", repo.calls.last())
    }

    @Test fun advanceIsIgnoredWhenNoNextStep() = runTest {
        val vm = OwnerOrdersViewModel(1, repo)
        vm.onIntent(OwnerOrdersIntent.Advance(anOrder(5, OrderStatus.PLACED)))
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun failedActionSurfacesMessageAndClearsBusy() = runTest {
        repo.action = ApiResult.Rejected(409, "Order already accepted", false)
        val vm = OwnerOrdersViewModel(1, repo)
        vm.onIntent(OwnerOrdersIntent.Accept(5))
        assertEquals("Order already accepted", vm.state.value.message)
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test fun ordersErrorShownOnlyBeforeFirstSuccess() = runTest {
        repo.orders = ApiResult.NetworkError("x")
        val vm = OwnerOrdersViewModel(1, repo)
        assertTrue(vm.state.value.orders is Load.Error)
        repo.orders = ApiResult.Success(OrderPage(emptyList(), 1, 50, 0))
        vm.onIntent(OwnerOrdersIntent.Refresh)
        assertTrue(vm.state.value.orders is Load.Success)
        repo.orders = ApiResult.NetworkError("x")
        vm.onIntent(OwnerOrdersIntent.Refresh)
        assertTrue(vm.state.value.orders is Load.Success)
    }

    @Test fun menuToggleUpdatesItemInPlace() = runTest {
        val vm = OwnerMenuViewModel(1, repo)
        vm.toggleAvailability(food(1))
        val items = (vm.state.value.menu as Load.Success).data.items
        assertFalse(items.first { it.id == 1L }.available)
        assertTrue(items.first { it.id == 2L }.available)
    }

    @Test fun menuSaveValidatesThenCallsAndCloses() = runTest {
        val vm = OwnerMenuViewModel(1, repo)
        var closed = 0
        vm.save(null, "Parotta", "", "abc", null, true) { closed++ }
        assertTrue(repo.calls.isEmpty())
        assertEquals(0, closed)
        vm.save(null, "Parotta", "", "12.50", null, true) { closed++ }
        assertEquals(listOf("createItem"), repo.calls)
        assertEquals(1, closed)
        vm.save(1, "Parotta", "", "12.50", null, true) { closed++ }
        assertEquals("updateItem", repo.calls.last())
    }

    @Test fun menuSaveFailureKeepsEditorOpen() = runTest {
        repo.itemResult = ApiResult.Rejected(400, "Bad", false)
        val vm = OwnerMenuViewModel(1, repo)
        var closed = 0
        vm.save(null, "Parotta", "", "12", null, true) { closed++ }
        assertEquals(0, closed)
        assertEquals("Bad", vm.state.value.message)
        assertFalse(vm.state.value.saving)
    }

    @Test fun dashboardLoads() = runTest {
        val vm = OwnerDashboardViewModel(repo)
        assertEquals("100.00", (vm.state.value.dashboard as Load.Success).data.todaySales)
    }
}
