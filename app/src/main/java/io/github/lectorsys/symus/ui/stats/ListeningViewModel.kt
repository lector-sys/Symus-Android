package io.github.lectorsys.symus.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.library.Listening
import io.github.lectorsys.symus.library.ListeningSummary
import io.github.lectorsys.symus.library.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ListeningUiState(
    val rangeIndex: Int = 1,
    val summary: ListeningSummary? = null
)

/** A selectable period, named the way the answer will name it. */
data class Range(val label: String, val of: (Long) -> Window)

@HiltViewModel
class ListeningViewModel @Inject constructor(
    private val listening: Listening
) : ViewModel() {

    val ranges = listOf(
        Range("Week") { Window.last7Days(it) },
        Range("Month") { Window.last30Days(it) },
        Range("Year") { Window.last365Days(it) },
        Range("All time") { Window.allTime(it) }
    )

    private val _state = MutableStateFlow(ListeningUiState())
    val state: StateFlow<ListeningUiState> = _state.asStateFlow()

    init {
        load(_state.value.rangeIndex)
    }

    fun setRange(index: Int) {
        if (index == _state.value.rangeIndex) return
        // Cleared first so the previous period's numbers are never shown under
        // the new period's label, which for a second reads as a real change.
        _state.value = ListeningUiState(rangeIndex = index, summary = null)
        load(index)
    }

    private fun load(index: Int) {
        viewModelScope.launch {
            val window = ranges[index].of(System.currentTimeMillis())
            val summary = runCatching { listening.summary(window, limit = 8) }.getOrNull()
            if (_state.value.rangeIndex == index) {
                _state.value = _state.value.copy(summary = summary)
            }
        }
    }
}
