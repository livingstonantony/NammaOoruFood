package dev.livin.nammaoorufood.customer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.cart.CartState
import dev.livin.nammaoorufood.customer.cart.CartStore
import dev.livin.nammaoorufood.customer.data.ShopRepository
import dev.livin.nammaoorufood.customer.data.errorMessage
import dev.livin.nammaoorufood.network.OrderLineRequest
import dev.livin.nammaoorufood.network.PlaceOrderRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CartScreenState(
    val cart: CartState = CartState(),
    val note: String = "",
    val placing: Boolean = false,
    val error: String? = null,
    val placedOrderId: Long? = null,
)

sealed interface CartIntent {
    data class Increment(val itemId: Long) : CartIntent
    data class Decrement(val itemId: Long) : CartIntent
    data class Remove(val itemId: Long) : CartIntent
    data class NoteChanged(val value: String) : CartIntent
    data object PlaceOrder : CartIntent
    data object OrderShown : CartIntent
}

class CartViewModel(private val repo: ShopRepository, private val cart: CartStore) : ViewModel() {
    private val _state = MutableStateFlow(CartScreenState(cart = cart.state.value))
    val state: StateFlow<CartScreenState> = _state.asStateFlow()

    init {
        cart.state.onEach { c -> _state.update { it.copy(cart = c, error = null) } }.launchIn(viewModelScope)
    }

    fun onIntent(intent: CartIntent) {
        when (intent) {
            is CartIntent.Increment -> cart.increment(intent.itemId)
            is CartIntent.Decrement -> cart.decrement(intent.itemId)
            is CartIntent.Remove -> cart.remove(intent.itemId)
            is CartIntent.NoteChanged -> _state.update { it.copy(note = intent.value.take(MAX_NOTE)) }
            CartIntent.PlaceOrder -> place()
            CartIntent.OrderShown -> _state.update { it.copy(placedOrderId = null) }
        }
    }

    private fun place() {
        val s = _state.value
        val hotelId = s.cart.hotelId
        if (s.placing || s.cart.isEmpty || hotelId == null) return // blocks duplicate submissions
        _state.update { it.copy(placing = true, error = null) }
        viewModelScope.launch {
            val request = PlaceOrderRequest(
                hotelId = hotelId,
                items = s.cart.lines.map { OrderLineRequest(it.itemId, it.quantity) },
                note = s.note.trim().ifEmpty { null },
            )
            when (val result = repo.placeOrder(request, s.cart.checkoutKey)) {
                is ApiResult.Success -> {
                    cart.clear()
                    _state.update { it.copy(placing = false, note = "", placedOrderId = result.value.id) }
                }
                else -> _state.update { it.copy(placing = false, error = result.errorMessage()) }
            }
        }
    }

    private companion object { const val MAX_NOTE = 255 }
}
