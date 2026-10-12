package io.github.lectorsys.symus.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lectorsys.symus.ai.LlmAvailability
import io.github.lectorsys.symus.ai.LlmRuntime
import io.github.lectorsys.symus.ai.ModelCatalog
import io.github.lectorsys.symus.ai.ModelSpec
import io.github.lectorsys.symus.ai.ModelState
import io.github.lectorsys.symus.ai.ModelStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelUiState(
    val install: ModelState = ModelState.Absent,
    val availability: LlmAvailability = LlmAvailability.Ready,
    val loadedName: String? = null,
    val loadError: String? = null,
    val freeBytes: Long = 0,
    /** Bytes already transferred per catalogue entry, so "Resume" can say how much. */
    val partial: Map<String, Long> = emptyMap()
)

@HiltViewModel
class ModelViewModel @Inject constructor(
    private val store: ModelStore,
    private val runtime: LlmRuntime
) : ViewModel() {

    private val _state = MutableStateFlow(ModelUiState())
    val state: StateFlow<ModelUiState> = _state.asStateFlow()

    private var transfer: Job? = null

    init {
        store.refresh()
        viewModelScope.launch {
            combine(store.state, runtime.loaded) { install, loaded -> install to loaded }
                .collect { (install, loaded) ->
                    _state.value = _state.value.copy(
                        install = install,
                        loadedName = loaded?.name,
                        availability = runtime.availability(store.installedSpec()),
                        freeBytes = store.freeBytes(),
                        partial = ModelCatalog.entries.associate { it.id to store.partialBytes(it) }
                    )
                }
        }
    }

    fun download(spec: ModelSpec) {
        transfer?.cancel()
        transfer = viewModelScope.launch { store.download(spec) }
    }

    fun import(uri: Uri) {
        transfer?.cancel()
        transfer = viewModelScope.launch {
            store.importFrom(uri, uri.lastPathSegment ?: "that file")
        }
    }

    /**
     * Cancels the transfer without discarding what arrived. Throwing away 400 MB
     * because someone tapped Cancel on a train would be the wrong reading of the
     * word.
     */
    fun cancel() {
        transfer?.cancel()
        transfer = null
    }

    fun discardPartial() {
        store.discardPartial()
        _state.value = _state.value.copy(
            partial = ModelCatalog.entries.associate { it.id to store.partialBytes(it) }
        )
    }

    fun load() {
        _state.value = _state.value.copy(loadError = null)
        viewModelScope.launch {
            val result = runtime.load()
            _state.value = _state.value.copy(loadError = result.exceptionOrNull()?.message)
        }
    }

    fun delete() {
        viewModelScope.launch {
            // Unload first: deleting a file the native runtime still has mapped
            // is how a crash-on-next-generation gets introduced.
            runtime.unload()
            store.delete()
        }
    }
}
