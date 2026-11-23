package dev.livin.nammaoorufood.owner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.data.errorMessage
import dev.livin.nammaoorufood.customer.data.toLoad
import dev.livin.nammaoorufood.network.*
import dev.livin.nammaoorufood.owner.data.OwnerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---- Hotel (profile, onboarding, hours, accepting switch) ----

data class OwnerHotelState(
    val hotels: Load<List<HotelDto>> = Load.Loading,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val hotel: HotelDto? get() = (hotels as? Load.Success)?.data?.firstOrNull()
}

sealed interface OwnerHotelIntent {
    data object Refresh : OwnerHotelIntent
    data object DismissMessage : OwnerHotelIntent
    data class CreateHotel(val name: String, val description: String, val address: String, val phone: String) : OwnerHotelIntent
    data class UpdateHotel(val id: Long, val name: String, val description: String, val address: String, val phone: String) : OwnerHotelIntent
    data class SetHours(val id: Long, val rows: List<HoursRow>) : OwnerHotelIntent
    data class SetAccepting(val id: Long, val accepting: Boolean) : OwnerHotelIntent
}

class OwnerHotelViewModel(
    private val repo: OwnerRepository,
    private val eventBus: LiveEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(OwnerHotelState())
    val state: StateFlow<OwnerHotelState> = _state.asStateFlow()

    init {
        refresh()
        eventBus.events
            .onEach { event ->
                if (event.type == "HOTEL_UPDATED") {
                    refresh()
                }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: OwnerHotelIntent) {
        when (intent) {
            OwnerHotelIntent.Refresh -> refresh()
            OwnerHotelIntent.DismissMessage -> dismissMessage()
            is OwnerHotelIntent.CreateHotel -> createHotel(intent.name, intent.description, intent.address, intent.phone)
            is OwnerHotelIntent.UpdateHotel -> updateHotel(intent.id, intent.name, intent.description, intent.address, intent.phone)
            is OwnerHotelIntent.SetHours -> setHours(intent.id, intent.rows)
            is OwnerHotelIntent.SetAccepting -> setAccepting(intent.id, intent.accepting)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val result = repo.hotels()
            _state.update { if (result is ApiResult.Success || it.hotels !is Load.Success) it.copy(hotels = result.toLoad()) else it }
        }
    }

    private fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun mutate(action: suspend () -> ApiResult<HotelDto>, onDone: (() -> Unit)? = null) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            when (val r = action()) {
                is ApiResult.Success -> {
                    _state.update { s ->
                        val list = (s.hotels as? Load.Success)?.data.orEmpty()
                        val updated = if (list.any { it.id == r.value.id }) list.map { if (it.id == r.value.id) r.value else it } else list + r.value
                        s.copy(hotels = Load.Success(updated), saving = false, message = "Saved")
                    }
                    onDone?.invoke()
                }
                else -> _state.update { it.copy(saving = false, message = r.errorMessage()) }
            }
        }
    }

    private fun createHotel(name: String, description: String, address: String, phone: String) {
        validateHotelForm(name, address)?.let { return _state.update { s -> s.copy(message = it) } }
        mutate({ repo.createHotel(request(name, description, address, phone)) })
    }

    private fun updateHotel(id: Long, name: String, description: String, address: String, phone: String) {
        validateHotelForm(name, address)?.let { return _state.update { s -> s.copy(message = it) } }
        mutate({ repo.updateHotel(id, request(name, description, address, phone)) })
    }

    private fun setHours(id: Long, rows: List<HoursRow>) {
        when (val r = buildHours(rows)) {
            is HoursResult.Invalid -> _state.update { it.copy(message = r.message) }
            is HoursResult.Valid -> mutate({ repo.setHours(id, r.hours) })
        }
    }

    private fun setAccepting(id: Long, accepting: Boolean) = mutate({ repo.setAcceptingOrders(id, accepting) })

    private fun request(name: String, description: String, address: String, phone: String) =
        HotelRequest(name.trim(), description.trim().ifEmpty { null }, address.trim(), phone.trim().ifEmpty { null })
}

// ---- Dashboard ----

data class OwnerDashboardState(val dashboard: Load<OwnerDashboardDto> = Load.Loading)

sealed interface OwnerDashboardIntent {
    data object Refresh : OwnerDashboardIntent
}

class OwnerDashboardViewModel(
    private val repo: OwnerRepository,
    private val eventBus: LiveEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(OwnerDashboardState())
    val state: StateFlow<OwnerDashboardState> = _state.asStateFlow()

    init {
        refresh()
        eventBus.events
            .onEach { event ->
                if (event.type == "ORDER_UPDATED" || event.type == "HOTEL_UPDATED") {
                    refresh()
                }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: OwnerDashboardIntent) {
        when (intent) {
            OwnerDashboardIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val r = repo.dashboard()
            _state.update { if (r is ApiResult.Success || it.dashboard !is Load.Success) OwnerDashboardState(r.toLoad()) else it }
        }
    }
}

// ---- Orders board ----

enum class OrderTab(val title: String, val statuses: Set<OrderStatus>) {
    INCOMING("Incoming", setOf(OrderStatus.PLACED)),
    PREPARING("Preparing", setOf(OrderStatus.ACCEPTED, OrderStatus.PREPARING)),
    READY("Ready", setOf(OrderStatus.READY_FOR_PICKUP)),
    DONE("Done", setOf(OrderStatus.COMPLETED, OrderStatus.REJECTED, OrderStatus.CANCELLED)),
}

/** The single forward action available to the owner after acceptance. */
fun OrderStatus.nextStep(): Pair<OrderStatus, String>? = when (this) {
    OrderStatus.ACCEPTED -> OrderStatus.PREPARING to "Start preparing"
    OrderStatus.PREPARING -> OrderStatus.READY_FOR_PICKUP to "Mark ready"
    OrderStatus.READY_FOR_PICKUP -> OrderStatus.COMPLETED to "Handed over"
    else -> null
}

data class OwnerOrdersState(
    val tab: OrderTab = OrderTab.INCOMING,
    val orders: Load<List<OrderDto>> = Load.Loading,
    val busy: Set<Long> = emptySet(),
    val message: String? = null,
)

sealed interface OwnerOrdersIntent {
    data class SelectTab(val tab: OrderTab) : OwnerOrdersIntent
    data object Refresh : OwnerOrdersIntent
    data class Accept(val orderId: Long) : OwnerOrdersIntent
    data class Reject(val orderId: Long) : OwnerOrdersIntent
    data class Advance(val order: OrderDto) : OwnerOrdersIntent
    data object DismissMessage : OwnerOrdersIntent
}

class OwnerOrdersViewModel(
    private val hotelId: Long,
    private val repo: OwnerRepository,
    private val eventBus: LiveEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(OwnerOrdersState())
    val state: StateFlow<OwnerOrdersState> = _state.asStateFlow()

    init {
        refresh()
        eventBus.events
            .onEach { event ->
                if (event.type == "ORDER_UPDATED") {
                    refresh()
                }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: OwnerOrdersIntent) {
        when (intent) {
            is OwnerOrdersIntent.SelectTab -> {
                _state.update { it.copy(tab = intent.tab, orders = Load.Loading) }
                refresh()
            }
            OwnerOrdersIntent.Refresh -> refresh()
            is OwnerOrdersIntent.Accept -> act(intent.orderId) { repo.accept(intent.orderId) }
            is OwnerOrdersIntent.Reject -> act(intent.orderId) { repo.reject(intent.orderId) }
            is OwnerOrdersIntent.Advance -> {
                val next = intent.order.status.nextStep()?.first ?: return
                act(intent.order.id) { repo.updateStatus(intent.order.id, next) }
            }
            OwnerOrdersIntent.DismissMessage -> _state.update { it.copy(message = null) }
        }
    }

    private fun refresh() {
        val requested = _state.value.tab
        viewModelScope.launch {
            val r = repo.orders(hotelId, requested.statuses)
            _state.update { s ->
                when {
                    s.tab != requested -> s // a newer tab selection owns the list now
                    r is ApiResult.Success -> s.copy(orders = Load.Success(r.value.items))
                    s.orders is Load.Success -> s
                    else -> s.copy(orders = Load.Error(r.errorMessage()))
                }
            }
        }
    }

    private fun act(orderId: Long, call: suspend () -> ApiResult<OrderDto>) {
        if (orderId in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + orderId, message = null) }
        viewModelScope.launch {
            val r = call()
            _state.update {
                it.copy(busy = it.busy - orderId, message = if (r is ApiResult.Success) null else r.errorMessage())
            }
            refresh() // also resyncs after a conflict (e.g. the order already moved on)
        }
    }
}

// ---- Menu ----

data class OwnerMenuState(
    val menu: Load<MenuResponse> = Load.Loading,
    val busy: Set<Long> = emptySet(),
    val saving: Boolean = false,
    val message: String? = null,
)

sealed interface OwnerMenuIntent {
    data object Refresh : OwnerMenuIntent
    data object DismissMessage : OwnerMenuIntent
    data class AddCategory(val name: String, val onAdded: (Long) -> Unit = {}) : OwnerMenuIntent
    data class ToggleAvailability(val item: FoodItemDto) : OwnerMenuIntent
    data class UpdateStockQuantity(val item: FoodItemDto, val newQuantity: Int?) : OwnerMenuIntent
    data class Save(
        val itemId: Long?,
        val name: String,
        val description: String,
        val price: String,
        val categoryId: Long?,
        val available: Boolean,
        val quantityAvailable: Int?,
        val onSaved: () -> Unit,
    ) : OwnerMenuIntent
    data class Delete(val itemId: Long) : OwnerMenuIntent
}

class OwnerMenuViewModel(
    private val hotelId: Long,
    private val repo: OwnerRepository,
    private val eventBus: LiveEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(OwnerMenuState())
    val state: StateFlow<OwnerMenuState> = _state.asStateFlow()

    init {
        refresh()
        eventBus.events
            .onEach { event ->
                if ((event.type == "MENU_UPDATED" || event.type == "ORDER_UPDATED") && (event.id == null || event.id == hotelId)) {
                    refresh()
                }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: OwnerMenuIntent) {
        when (intent) {
            OwnerMenuIntent.Refresh -> refresh()
            OwnerMenuIntent.DismissMessage -> dismissMessage()
            is OwnerMenuIntent.AddCategory -> addCategory(intent.name, intent.onAdded)
            is OwnerMenuIntent.ToggleAvailability -> toggleAvailability(intent.item)
            is OwnerMenuIntent.UpdateStockQuantity -> updateStockQuantity(intent.item, intent.newQuantity)
            is OwnerMenuIntent.Save -> save(intent.itemId, intent.name, intent.description, intent.price, intent.categoryId, intent.available, intent.quantityAvailable, intent.onSaved)
            is OwnerMenuIntent.Delete -> delete(intent.itemId)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val r = repo.menu(hotelId)
            _state.update { if (r is ApiResult.Success || it.menu !is Load.Success) it.copy(menu = r.toLoad()) else it }
        }
    }

    private fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun addCategory(name: String, onAdded: (Long) -> Unit = {}) {
        validateCategoryName(name)?.let { return _state.update { s -> s.copy(message = it) } }
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val r = repo.createCategory(hotelId, name.trim())
            _state.update { it.copy(saving = false, message = if (r is ApiResult.Success) null else r.errorMessage()) }
            if (r is ApiResult.Success) {
                refresh()
                onAdded(r.value.id)
            }
        }
    }

    private fun toggleAvailability(item: FoodItemDto) {
        if (item.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + item.id, message = null) }
        viewModelScope.launch {
            val r = repo.setItemAvailability(hotelId, item.id, available = !item.available)
            _state.update { s ->
                val menu = (s.menu as? Load.Success)?.data
                s.copy(
                    busy = s.busy - item.id,
                    menu = if (r is ApiResult.Success && menu != null) Load.Success(menu.copy(items = menu.items.map { if (it.id == item.id) r.value else it })) else s.menu,
                    message = if (r is ApiResult.Success) null else r.errorMessage(),
                )
            }
        }
    }

    private fun updateStockQuantity(item: FoodItemDto, newQuantity: Int?) {
        if (item.id in _state.value.busy) return
        val target = newQuantity?.coerceAtLeast(0)
        val available = if (target != null) target > 0 else item.available
        _state.update { it.copy(busy = it.busy + item.id, message = null) }
        viewModelScope.launch {
            val r = repo.setItemAvailability(hotelId, item.id, available = available, quantityAvailable = target)
            _state.update { s ->
                val menu = (s.menu as? Load.Success)?.data
                s.copy(
                    busy = s.busy - item.id,
                    menu = if (r is ApiResult.Success && menu != null) Load.Success(menu.copy(items = menu.items.map { if (it.id == item.id) r.value else it })) else s.menu,
                    message = if (r is ApiResult.Success) null else r.errorMessage(),
                )
            }
        }
    }

    /** [itemId] null creates a new item. [onSaved] runs only on success so the editor can close. */
    private fun save(itemId: Long?, name: String, description: String, price: String, categoryId: Long?, available: Boolean, quantityAvailable: Int?, onSaved: () -> Unit) {
        if (_state.value.saving) return
        (validateItemName(name) ?: validatePrice(price))?.let { return _state.update { s -> s.copy(message = it) } }
        val request = FoodItemRequest(name.trim(), description.trim().ifEmpty { null }, price.trim(), categoryId, available, quantityAvailable)
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val r = if (itemId == null) repo.createItem(hotelId, request) else repo.updateItem(hotelId, itemId, request)
            _state.update { it.copy(saving = false, message = if (r is ApiResult.Success) null else r.errorMessage()) }
            if (r is ApiResult.Success) {
                refresh()
                onSaved()
            }
        }
    }

    private fun delete(itemId: Long) {
        if (itemId in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + itemId, message = null) }
        viewModelScope.launch {
            val r = repo.deleteItem(hotelId, itemId)
            _state.update { it.copy(busy = it.busy - itemId, message = if (r is ApiResult.Success) null else r.errorMessage()) }
            if (r is ApiResult.Success) refresh()
        }
    }
}
