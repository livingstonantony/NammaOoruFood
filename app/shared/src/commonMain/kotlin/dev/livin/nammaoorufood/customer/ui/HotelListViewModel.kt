package dev.livin.nammaoorufood.customer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.data.ShopRepository
import dev.livin.nammaoorufood.customer.data.errorMessage
import dev.livin.nammaoorufood.network.HotelDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HotelListState(
    val query: String = "",
    val openOnly: Boolean = false,
    val hotels: Load<List<HotelDto>> = Load.Loading,
)

sealed interface HotelListIntent {
    data class QueryChanged(val value: String) : HotelListIntent
    data object ToggleOpenOnly : HotelListIntent
    data object Refresh : HotelListIntent
}

class HotelListViewModel(private val repo: ShopRepository) : ViewModel() {
    private val _state = MutableStateFlow(HotelListState())
    val state: StateFlow<HotelListState> = _state.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun onIntent(intent: HotelListIntent) {
        when (intent) {
            is HotelListIntent.QueryChanged -> { _state.update { it.copy(query = intent.value) }; load() }
            HotelListIntent.ToggleOpenOnly -> { _state.update { it.copy(openOnly = !it.openOnly) }; load() }
            HotelListIntent.Refresh -> load()
        }
    }

    private fun load() {
        job?.cancel() // a newer search supersedes an in-flight one
        val s = _state.value
        _state.update { it.copy(hotels = Load.Loading) }
        job = viewModelScope.launch {
            val result = repo.hotels(s.query, s.openOnly)
            val hotels = if (result is ApiResult.Success) Load.Success(result.value.items) else Load.Error(result.errorMessage())
            _state.update { it.copy(hotels = hotels) }
        }
    }
}
