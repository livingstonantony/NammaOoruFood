package dev.livin.nammaoorufood.customer.cart

import dev.livin.nammaoorufood.network.FoodItemDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class CartLine(val itemId: Long, val name: String, val unitPricePaise: Long, val quantity: Int) {
    val linePaise get() = unitPricePaise * quantity
}

/** [checkoutKey] changes on every edit so a retried checkout can never replay an earlier, different cart. */
data class CartState(
    val hotelId: Long? = null,
    val hotelName: String? = null,
    val lines: List<CartLine> = emptyList(),
    val checkoutKey: String = "",
) {
    val totalPaise get() = lines.sumOf { it.linePaise }
    val itemCount get() = lines.sumOf { it.quantity }
    val isEmpty get() = lines.isEmpty()
    fun quantityOf(itemId: Long) = lines.firstOrNull { it.itemId == itemId }?.quantity ?: 0
}

enum class AddResult { ADDED, HOTEL_CONFLICT, LIMIT_REACHED, UNAVAILABLE }

@OptIn(ExperimentalUuidApi::class)
class CartStore(private val newKey: () -> String = { Uuid.random().toString() }) {
    private val _state = MutableStateFlow(CartState(checkoutKey = newKey()))
    val state: StateFlow<CartState> = _state.asStateFlow()

    fun add(hotelId: Long, hotelName: String, item: FoodItemDto): AddResult {
        if (!item.available) return AddResult.UNAVAILABLE
        val current = _state.value
        if (current.hotelId != null && current.hotelId != hotelId && !current.isEmpty) return AddResult.HOTEL_CONFLICT
        val existing = current.lines.firstOrNull { it.itemId == item.id }
        if (existing != null && existing.quantity >= MAX_QUANTITY) return AddResult.LIMIT_REACHED
        if (existing == null && current.lines.size >= MAX_LINES) return AddResult.LIMIT_REACHED
        _state.update { s ->
            val lines = if (existing == null) {
                s.lines + CartLine(item.id, item.name, Money.parsePaise(item.price), 1)
            } else {
                s.lines.map { if (it.itemId == item.id) it.copy(quantity = it.quantity + 1) else it }
            }
            s.copy(hotelId = hotelId, hotelName = hotelName, lines = lines, checkoutKey = newKey())
        }
        return AddResult.ADDED
    }

    fun replaceWith(hotelId: Long, hotelName: String, item: FoodItemDto): AddResult {
        clear()
        return add(hotelId, hotelName, item)
    }

    fun increment(itemId: Long) = update(itemId) { if (it.quantity < MAX_QUANTITY) it.copy(quantity = it.quantity + 1) else it }

    fun decrement(itemId: Long) = update(itemId) { if (it.quantity > 1) it.copy(quantity = it.quantity - 1) else null }

    fun remove(itemId: Long) = update(itemId) { null }

    fun clear() = _state.update { CartState(checkoutKey = newKey()) }

    private fun update(itemId: Long, transform: (CartLine) -> CartLine?) = _state.update { s ->
        if (s.lines.none { it.itemId == itemId }) return@update s
        val lines = s.lines.mapNotNull { if (it.itemId == itemId) transform(it) else it }
        if (lines.isEmpty()) CartState(checkoutKey = newKey())
        else s.copy(lines = lines, checkoutKey = newKey())
    }

    companion object {
        const val MAX_QUANTITY = 20
        const val MAX_LINES = 30
    }
}
