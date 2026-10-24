package dev.livin.nammaoorufood.admin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.livin.nammaoorufood.admin.data.AdminRepository
import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.data.errorMessage
import dev.livin.nammaoorufood.customer.data.toLoad
import dev.livin.nammaoorufood.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OverviewState(
    val overview: Load<PlatformOverview> = Load.Loading,
    val sales: Load<HotelSalesReport> = Load.Loading,
    val from: String = "",
    val to: String = "",
    val rangeError: String? = null,
)

private val IsoDate = Regex("""\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""")

/** Blank bounds mean "server default"; ISO dates compare correctly as strings. */
fun validateDateRange(from: String, to: String): String? = when {
    from.isNotBlank() && !IsoDate.matches(from.trim()) -> "Enter the start date as YYYY-MM-DD"
    to.isNotBlank() && !IsoDate.matches(to.trim()) -> "Enter the end date as YYYY-MM-DD"
    from.isNotBlank() && to.isNotBlank() && from.trim() > to.trim() -> "Start date must not be after end date"
    else -> null
}

class AdminOverviewViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(OverviewState())
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    init { refresh() }

    fun setRange(from: String, to: String) = _state.update { it.copy(from = from, to = to, rangeError = null) }

    fun applyRange() {
        validateDateRange(_state.value.from, _state.value.to)?.let { e -> return _state.update { it.copy(rangeError = e) } }
        refresh(replace = true)
    }

    fun refresh(replace: Boolean = false) {
        val (from, to) = _state.value.let { it.from.trim().ifEmpty { null } to it.to.trim().ifEmpty { null } }
        viewModelScope.launch {
            val o = repo.overview(from, to)
            val s = repo.hotelSales(from, to)
            _state.update {
                it.copy(
                    overview = if (o is ApiResult.Success || replace || it.overview !is Load.Success) o.toLoad() else it.overview,
                    sales = if (s is ApiResult.Success || replace || it.sales !is Load.Success) s.toLoad() else it.sales,
                )
            }
        }
    }
}

data class AdminHotelsState(
    val hotels: Load<List<HotelDto>> = Load.Loading,
    val query: String = "",
    val busy: Set<Long> = emptySet(),
    val message: String? = null,
)

class AdminHotelsViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminHotelsState())
    val state: StateFlow<AdminHotelsState> = _state.asStateFlow()

    init { refresh() }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        refresh()
    }

    fun refresh() {
        val requested = _state.value.query
        viewModelScope.launch {
            val r = repo.hotels(requested)
            _state.update { s ->
                when {
                    s.query != requested -> s
                    r is ApiResult.Success || s.hotels !is Load.Success -> s.copy(hotels = r.toLoad())
                    else -> s
                }
            }
        }
    }

    fun setApproval(hotelId: Long, status: String) {
        if (hotelId in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + hotelId, message = null) }
        viewModelScope.launch {
            val r = repo.setApproval(hotelId, status)
            _state.update { s ->
                val list = (s.hotels as? Load.Success)?.data
                s.copy(
                    busy = s.busy - hotelId,
                    hotels = if (r is ApiResult.Success && list != null) Load.Success(list.map { if (it.id == hotelId) r.value else it }) else s.hotels,
                    message = if (r is ApiResult.Success) null else r.errorMessage(),
                )
            }
        }
    }
}

data class AdminCustomersState(
    val page: Load<CustomerPage> = Load.Loading,
    val query: String = "",
    val pageNumber: Int = 1,
    val busy: Set<Long> = emptySet(),
    val message: String? = null,
)

class AdminCustomersViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminCustomersState())
    val state: StateFlow<AdminCustomersState> = _state.asStateFlow()

    init { refresh() }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q, pageNumber = 1, page = Load.Loading) }
        refresh()
    }

    fun goToPage(n: Int) {
        if (n < 1) return
        _state.update { it.copy(pageNumber = n, page = Load.Loading) }
        refresh()
    }

    fun refresh() {
        val (q, n) = _state.value.let { it.query to it.pageNumber }
        viewModelScope.launch {
            val r = repo.customers(q, n)
            _state.update { s ->
                when {
                    s.query != q || s.pageNumber != n -> s
                    r is ApiResult.Success || s.page !is Load.Success -> s.copy(page = r.toLoad())
                    else -> s
                }
            }
        }
    }

    fun setActive(id: Long, active: Boolean) {
        if (id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + id, message = null) }
        viewModelScope.launch {
            val r = repo.setCustomerActive(id, active)
            _state.update { s ->
                val page = (s.page as? Load.Success)?.data
                s.copy(
                    busy = s.busy - id,
                    page = if (r is ApiResult.Success && page != null) Load.Success(page.copy(items = page.items.map { if (it.id == id) r.value else it })) else s.page,
                    message = if (r is ApiResult.Success) null else r.errorMessage(),
                )
            }
        }
    }
}

data class AdminOrdersState(
    val page: Load<OrderPage> = Load.Loading,
    val status: OrderStatus? = null,
    val pageNumber: Int = 1,
)

class AdminOrdersViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminOrdersState())
    val state: StateFlow<AdminOrdersState> = _state.asStateFlow()

    init { refresh() }

    fun setStatus(status: OrderStatus?) {
        _state.update { it.copy(status = status, pageNumber = 1, page = Load.Loading) }
        refresh()
    }

    fun goToPage(n: Int) {
        if (n < 1) return
        _state.update { it.copy(pageNumber = n, page = Load.Loading) }
        refresh()
    }

    fun refresh() {
        val (st, n) = _state.value.let { it.status to it.pageNumber }
        viewModelScope.launch {
            val r = repo.orders(st, null, n)
            _state.update { s ->
                when {
                    s.status != st || s.pageNumber != n -> s
                    r is ApiResult.Success || s.page !is Load.Success -> s.copy(page = r.toLoad())
                    else -> s
                }
            }
        }
    }
}

/** True when another page exists after [page]. */
fun hasNextPage(page: Int, pageSize: Int, total: Int) = page * pageSize < total
