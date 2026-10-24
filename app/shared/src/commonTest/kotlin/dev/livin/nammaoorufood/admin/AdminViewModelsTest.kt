package dev.livin.nammaoorufood.admin

import dev.livin.nammaoorufood.admin.data.AdminRepository
import dev.livin.nammaoorufood.admin.ui.*
import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.aHotel
import dev.livin.nammaoorufood.customer.anOrder
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

private fun cust(id: Long, active: Boolean = true) = CustomerDto(id, "u$id", "User $id", null, active, "t", 0)

class FakeAdmin : AdminRepository {
    var hotels: ApiResult<List<HotelDto>> = ApiResult.Success(listOf(aHotel(1).copy(approvalStatus = "PENDING")))
    var approval: ApiResult<HotelDto>? = null
    var customers: ApiResult<CustomerPage> = ApiResult.Success(CustomerPage(listOf(cust(1), cust(2)), 1, 20, 45))
    var activeResult: ApiResult<CustomerDto>? = null
    val customerCalls = mutableListOf<Pair<String?, Int>>()
    val orderCalls = mutableListOf<Pair<OrderStatus?, Int>>()
    val approvals = mutableListOf<String>()

    val ranges = mutableListOf<Pair<String?, String?>>()
    override suspend fun overview(from: String?, to: String?): ApiResult<PlatformOverview> { ranges += from to to; return ApiResult.Success(PlatformOverview("a", "b", 1, 2, 3, "9.00", 4, 5, 6, 7)) }
    override suspend fun hotelSales(from: String?, to: String?) = ApiResult.Success(HotelSalesReport("a", "b", emptyList()))
    override suspend fun hotels(query: String?) = hotels
    override suspend fun setApproval(hotelId: Long, status: String): ApiResult<HotelDto> {
        approvals += status
        return approval ?: ApiResult.Success(aHotel(hotelId).copy(approvalStatus = status))
    }
    override suspend fun customers(query: String?, page: Int): ApiResult<CustomerPage> { customerCalls += query to page; return customers }
    override suspend fun setCustomerActive(id: Long, active: Boolean) = activeResult ?: ApiResult.Success(cust(id, active))
    override suspend fun orders(status: OrderStatus?, hotelId: Long?, page: Int): ApiResult<OrderPage> {
        orderCalls += status to page
        return ApiResult.Success(OrderPage(listOf(anOrder(1)), page, 20, 30))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelsTest {
    private val repo = FakeAdmin()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun dateRangeIsValidatedThenSentToServer() = runTest {
        val vm = AdminOverviewViewModel(repo)
        repo.ranges.clear()
        vm.setRange("2026-10-05", "2026-10-01")
        vm.applyRange()
        assertNotNull(vm.state.value.rangeError)
        assertTrue(repo.ranges.isEmpty())
        vm.setRange("10/05", "")
        vm.applyRange()
        assertNotNull(vm.state.value.rangeError)
        vm.setRange("2026-10-01", "2026-10-05")
        vm.applyRange()
        assertNull(vm.state.value.rangeError)
        assertEquals(listOf<Pair<String?, String?>>("2026-10-01" to "2026-10-05"), repo.ranges)
    }

    @Test fun overviewLoads() = runTest {
        val vm = AdminOverviewViewModel(repo)
        assertEquals("9.00", (vm.state.value.overview as Load.Success).data.totalSales)
    }

    @Test fun approvalUpdatesHotelInPlace() = runTest {
        val vm = AdminHotelsViewModel(repo)
        vm.setApproval(1, "APPROVED")
        assertEquals("APPROVED", (vm.state.value.hotels as Load.Success).data.single().approvalStatus)
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test fun failedApprovalShowsMessageAndKeepsHotel() = runTest {
        repo.approval = ApiResult.Rejected(409, "Not allowed", false)
        val vm = AdminHotelsViewModel(repo)
        vm.setApproval(1, "APPROVED")
        assertEquals("Not allowed", vm.state.value.message)
        assertEquals("PENDING", (vm.state.value.hotels as Load.Success).data.single().approvalStatus)
    }

    @Test fun customerPagingAndSearchResetPage() = runTest {
        val vm = AdminCustomersViewModel(repo)
        vm.goToPage(2)
        assertEquals("" to 2, repo.customerCalls.last())
        vm.setQuery("ann")
        assertEquals("ann" to 1, repo.customerCalls.last())
        vm.goToPage(0)
        assertEquals(1, vm.state.value.pageNumber)
    }

    @Test fun deactivateUpdatesRow() = runTest {
        val vm = AdminCustomersViewModel(repo)
        vm.setActive(1, false)
        val items = (vm.state.value.page as Load.Success).data.items
        assertFalse(items.first { it.id == 1L }.active)
        assertTrue(items.first { it.id == 2L }.active)
    }

    @Test fun ordersFilterResetsPage() = runTest {
        val vm = AdminOrdersViewModel(repo)
        vm.goToPage(2)
        vm.setStatus(OrderStatus.READY_FOR_PICKUP)
        assertEquals(OrderStatus.READY_FOR_PICKUP to 1, repo.orderCalls.last())
    }

    @Test fun nextPageMath() {
        assertTrue(hasNextPage(1, 20, 45))
        assertTrue(hasNextPage(2, 20, 45))
        assertFalse(hasNextPage(3, 20, 45))
        assertFalse(hasNextPage(1, 20, 20))
    }
}
