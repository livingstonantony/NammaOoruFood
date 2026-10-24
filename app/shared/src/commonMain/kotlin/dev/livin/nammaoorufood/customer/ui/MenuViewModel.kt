package dev.livin.nammaoorufood.customer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.cart.AddResult
import dev.livin.nammaoorufood.customer.cart.CartState
import dev.livin.nammaoorufood.customer.cart.CartStore
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.data.ShopRepository
import dev.livin.nammaoorufood.customer.data.errorMessage
import dev.livin.nammaoorufood.network.FoodItemDto
import dev.livin.nammaoorufood.network.HotelDto
import dev.livin.nammaoorufood.network.MenuResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MenuContent(val hotel: HotelDto, val menu: MenuResponse)

data class MenuState(
    val content: Load<MenuContent> = Load.Loading,
    val cart: CartState = CartState(),
    val pendingConflict: FoodItemDto? = null,
    val message: String? = null,
)

sealed interface MenuIntent {
    data object Reload : MenuIntent
    data class Add(val item: FoodItemDto) : MenuIntent
    data class Increment(val itemId: Long) : MenuIntent
    data class Decrement(val itemId: Long) : MenuIntent
    data object ConfirmReplaceCart : MenuIntent
    data object DismissConflict : MenuIntent
    data object DismissMessage : MenuIntent
}

class MenuViewModel(private val hotelId: Long, private val repo: ShopRepository, private val cart: CartStore) : ViewModel() {
    private val _state = MutableStateFlow(MenuState(cart = cart.state.value))
    val state: StateFlow<MenuState> = _state.asStateFlow()

    init {
        cart.state.onEach { c -> _state.update { it.copy(cart = c) } }.launchIn(viewModelScope)
        load()
    }

    fun onIntent(intent: MenuIntent) {
        when (intent) {
            MenuIntent.Reload -> load()
            is MenuIntent.Add -> add(intent.item)
            is MenuIntent.Increment -> cart.increment(intent.itemId)
            is MenuIntent.Decrement -> cart.decrement(intent.itemId)
            MenuIntent.ConfirmReplaceCart -> {
                val item = _state.value.pendingConflict
                val hotel = (_state.value.content as? Load.Success)?.data?.hotel
                if (item != null && hotel != null) cart.replaceWith(hotel.id, hotel.name, item)
                _state.update { it.copy(pendingConflict = null) }
            }
            MenuIntent.DismissConflict -> _state.update { it.copy(pendingConflict = null) }
            MenuIntent.DismissMessage -> _state.update { it.copy(message = null) }
        }
    }

    private fun add(item: FoodItemDto) {
        val hotel = (_state.value.content as? Load.Success)?.data?.hotel ?: return
        when (cart.add(hotel.id, hotel.name, item)) {
            AddResult.ADDED -> Unit
            AddResult.HOTEL_CONFLICT -> _state.update { it.copy(pendingConflict = item) }
            AddResult.LIMIT_REACHED -> _state.update { it.copy(message = "You've reached the maximum quantity for this order.") }
            AddResult.UNAVAILABLE -> _state.update { it.copy(message = "${item.name} is currently unavailable.") }
        }
    }

    private fun load() {
        _state.update { it.copy(content = Load.Loading) }
        viewModelScope.launch {
            val hotel = repo.hotel(hotelId)
            val menu = repo.menu(hotelId)
            val content = when {
                hotel !is ApiResult.Success -> Load.Error(hotel.errorMessage())
                menu !is ApiResult.Success -> Load.Error(menu.errorMessage())
                else -> Load.Success(MenuContent(hotel.value, menu.value))
            }
            _state.update { it.copy(content = content) }
        }
    }
}
