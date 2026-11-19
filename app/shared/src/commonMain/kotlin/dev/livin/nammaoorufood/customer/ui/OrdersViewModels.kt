package dev.livin.nammaoorufood.customer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.data.ShopRepository
import dev.livin.nammaoorufood.customer.data.errorMessage
import dev.livin.nammaoorufood.network.NotificationDto
import dev.livin.nammaoorufood.network.OrderDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrdersState(val active: List<OrderDto> = emptyList(), val past: List<OrderDto> = emptyList(), val load: Load<Unit> = Load.Loading)

sealed interface OrdersIntent {
    data object Refresh : OrdersIntent
}

class OrdersViewModel(private val repo: ShopRepository) : ViewModel() {
    private val _state = MutableStateFlow(OrdersState())
    val state: StateFlow<OrdersState> = _state.asStateFlow()

    init { refresh() }

    fun onIntent(intent: OrdersIntent) {
        when (intent) {
            OrdersIntent.Refresh -> refresh()
        }
    }

    /** Silent when data is already on screen so periodic refreshes do not flicker. */
    private fun refresh() {
        viewModelScope.launch {
            val active = repo.activeOrders()
            val history = repo.orderHistory()
            when {
                active is ApiResult.Success && history is ApiResult.Success -> {
                    val activeIds = active.value.map { it.id }.toSet()
                    _state.update {
                        OrdersState(active.value, history.value.items.filter { o -> o.id !in activeIds }, Load.Success(Unit))
                    }
                }
                else -> _state.update {
                    if (it.load is Load.Success) it
                    else it.copy(load = Load.Error((if (active !is ApiResult.Success) active else history).errorMessage()))
                }
            }
        }
    }
}

data class OrderDetailState(val order: Load<OrderDto> = Load.Loading, val cancelling: Boolean = false, val actionError: String? = null)

sealed interface OrderDetailIntent {
    data object Refresh : OrderDetailIntent
    data object Cancel : OrderDetailIntent
}

class OrderDetailViewModel(private val orderId: Long, private val repo: ShopRepository) : ViewModel() {
    private val _state = MutableStateFlow(OrderDetailState())
    val state: StateFlow<OrderDetailState> = _state.asStateFlow()

    init { refresh() }

    fun onIntent(intent: OrderDetailIntent) {
        when (intent) {
            OrderDetailIntent.Refresh -> refresh()
            OrderDetailIntent.Cancel -> cancel()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            when (val result = repo.order(orderId)) {
                is ApiResult.Success -> _state.update { it.copy(order = Load.Success(result.value)) }
                else -> _state.update { if (it.order is Load.Success) it else it.copy(order = Load.Error(result.errorMessage())) }
            }
        }
    }

    private fun cancel() {
        if (_state.value.cancelling) return
        _state.update { it.copy(cancelling = true, actionError = null) }
        viewModelScope.launch {
            when (val result = repo.cancelOrder(orderId)) {
                is ApiResult.Success -> _state.update { it.copy(order = Load.Success(result.value), cancelling = false) }
                else -> {
                    _state.update { it.copy(cancelling = false, actionError = result.errorMessage()) }
                    refresh()
                }
            }
        }
    }
}

data class NotificationsState(val unread: List<NotificationDto> = emptyList())

sealed interface NotificationsIntent {
    data object Refresh : NotificationsIntent
    data class Dismiss(val id: Long) : NotificationsIntent
}

/** Polls unread notifications; the backend remains the source of truth for order status. */
class NotificationsViewModel(private val repo: ShopRepository) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsState())
    val state: StateFlow<NotificationsState> = _state.asStateFlow()

    fun onIntent(intent: NotificationsIntent) {
        when (intent) {
            NotificationsIntent.Refresh -> refresh()
            is NotificationsIntent.Dismiss -> dismiss(intent.id)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val result = repo.unreadNotifications()
            if (result is ApiResult.Success) _state.value = NotificationsState(result.value.items)
        }
    }

    private fun dismiss(id: Long) {
        _state.update { s -> s.copy(unread = s.unread.filterNot { it.id == id }) }
        viewModelScope.launch { repo.markNotificationRead(id) }
    }
}
