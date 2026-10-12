package io.github.lectorsys.symus.ui.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lectorsys.symus.data.ImportItemEntity
import io.github.lectorsys.symus.data.ImportJobEntity
import io.github.lectorsys.symus.data.ImportState
import io.github.lectorsys.symus.library.ImportCounts
import io.github.lectorsys.symus.library.PlaylistImport
import io.github.lectorsys.symus.library.Provider
import io.github.lectorsys.symus.library.parseSourceLink
import io.github.lectorsys.symus.library.ProviderAccess
import io.github.lectorsys.symus.library.setupInstructions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ImportUiState(
    val link: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    /**
     * A recognised provider this app cannot reach, with the reason.
     *
     * Kept separate from [message] because it is not an error the user caused
     * and not one a retry fixes — it wants a different kind of answer on screen.
     */
    val blocked: Pair<Provider, String>? = null,
    val openJobId: Long? = null,
    val counts: ImportCounts? = null,
    val items: List<ImportItemEntity> = emptyList(),
    val job: ImportJobEntity? = null,
    /** Where each credentialled provider stands, for the Connections block. */
    val access: Map<Provider, ProviderAccess> = emptyMap(),
    val showConnections: Boolean = false
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imports: PlaylistImport,
    private val spotify: io.github.lectorsys.symus.library.SpotifyAdapter,
    private val apple: io.github.lectorsys.symus.library.AppleAdapter
) : ViewModel() {

    /**
     * Re-read after every credential change rather than cached, because the
     * whole value of this block is that it says what is true right now.
     */
    fun refreshAccess() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                access = listOf(Provider.SPOTIFY, Provider.APPLE)
                    .associateWith { runCatching { imports.access(it) }.getOrElse {
                        ProviderAccess.Failed(it.message ?: "Could not read that setting.")
                    } }
            )
        }
    }

    fun toggleConnections() {
        val showing = !_state.value.showConnections
        _state.value = _state.value.copy(showConnections = showing)
        if (showing) refreshAccess()
    }

    fun setSpotifyClientId(value: String) = viewModelScope.launch {
        spotify.setClientId(value)
        refreshAccess()
    }

    /** Returns the URL the caller should open in a browser, or a reason not to. */
    fun spotifySignInUrl(onReady: (String) -> Unit) = viewModelScope.launch {
        spotify.beginSignIn().fold(
            onSuccess = onReady,
            onFailure = { _state.value = _state.value.copy(message = it.message) }
        )
    }

    fun spotifySignOut() = viewModelScope.launch {
        spotify.signOut()
        refreshAccess()
    }

    fun setAppleDeveloperToken(value: String) = viewModelScope.launch {
        val result = apple.setDeveloperToken(value)
        _state.value = _state.value.copy(message = result.exceptionOrNull()?.message)
        refreshAccess()
    }

    fun setAppleStorefront(value: String) = viewModelScope.launch {
        apple.setStorefront(value)
        refreshAccess()
    }

    fun setAppleUserToken(value: String) = viewModelScope.launch {
        apple.setMusicUserToken(value)
        refreshAccess()
    }

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    val jobs: StateFlow<List<ImportJobEntity>> = imports.jobs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The matching loop, kept so it can be stopped when the screen goes away. */
    private var matching: Job? = null

    fun setLink(text: String) {
        _state.value = _state.value.copy(link = text, message = null, blocked = null)
    }

    /**
     * Acts on whatever was pasted.
     *
     * A provider this app cannot reach is reported as such, with what it would
     * take and the file route that does work, rather than being attempted and
     * failing later with something vague.
     */
    fun submitLink() {
        val link = parseSourceLink(_state.value.link)
        if (link == null) {
            _state.value = _state.value.copy(
                message = "That does not look like a playlist link. YouTube and " +
                    "YouTube Music playlist links work; for anything else, import a file."
            )
            return
        }
        _state.value = _state.value.copy(busy = true, message = null, blocked = null)
        viewModelScope.launch {
            // Checked before a job row is written. Starting an import that
            // cannot run leaves a FAILED row the user has to clear by hand.
            when (val access = imports.access(link.provider)) {
                is ProviderAccess.Ready -> Unit
                is ProviderAccess.NeedsConfiguration -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        blocked = link.provider to
                            "${link.provider.label} needs ${access.what}. " +
                            link.provider.setupInstructions()
                    )
                    return@launch
                }

                is ProviderAccess.NeedsSignIn -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        blocked = link.provider to access.why
                    )
                    return@launch
                }

                is ProviderAccess.Restricted -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        blocked = link.provider to "${access.reason} ${access.remedy}"
                    )
                    return@launch
                }

                is ProviderAccess.Failed -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        blocked = link.provider to access.message
                    )
                    return@launch
                }
            }

            val jobId = imports.startLink(link)
            open(jobId)
            val result = imports.fetch(jobId)
            _state.value = _state.value.copy(
                busy = false,
                message = result.exceptionOrNull()?.message
            )
            refresh(jobId)
            runMatching(jobId)
        }
    }

    /**
     * Imports an export file.
     *
     * Read on a background thread and never held open: the picker grants
     * access to this one file for this one moment.
     */
    /**
     * The name the user would recognise, not the document id.
     *
     * A SAF uri's last path segment is an opaque provider id — "document:22" —
     * and using it as the playlist title produces an import nobody can find
     * again. OpenableColumns is the only thing that knows the real file name.
     */
    fun importFile(uri: Uri) {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "playlist.csv"
        importFile(uri, name)
    }

    fun importFile(uri: Uri, displayName: String) {
        _state.value = _state.value.copy(busy = true, message = null, blocked = null)
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                    }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    busy = false,
                    message = "That file could not be read."
                )
                return@launch
            }

            val started = imports.startFile(displayName, content)
            val jobId = started.getOrNull()
            if (jobId == null) {
                _state.value = _state.value.copy(
                    busy = false,
                    message = started.exceptionOrNull()?.message ?: "That file was not usable."
                )
                return@launch
            }
            open(jobId)
            _state.value = _state.value.copy(busy = false)
            runMatching(jobId)
        }
    }

    fun open(jobId: Long) {
        _state.value = _state.value.copy(openJobId = jobId)
        viewModelScope.launch { refresh(jobId) }
    }

    fun close() {
        matching?.cancel()
        matching = null
        _state.value = _state.value.copy(openJobId = null, items = emptyList(), counts = null)
    }

    /**
     * Matches in batches until nothing is pending.
     *
     * Cancellable and resumable: stopping the loop leaves every row already
     * written, and starting it again picks up exactly where it stopped.
     */
    fun runMatching(jobId: Long) {
        matching?.cancel()
        matching = viewModelScope.launch {
            while (true) {
                val done = runCatching { imports.matchNext(jobId) }.getOrDefault(0)
                refresh(jobId)
                if (done == 0) break
            }
        }
    }

    fun pauseMatching() {
        matching?.cancel()
        matching = null
    }

    fun choose(itemId: Long, videoId: String?) {
        val jobId = _state.value.openJobId ?: return
        viewModelScope.launch {
            imports.chooseMatch(itemId, videoId)
            refresh(jobId)
        }
    }

    fun retryUnresolved() {
        val jobId = _state.value.openJobId ?: return
        viewModelScope.launch {
            imports.retryUnresolved(jobId)
            runMatching(jobId)
        }
    }

    fun commit(onDone: (Long) -> Unit) {
        val jobId = _state.value.openJobId ?: return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = imports.commit(jobId)
            _state.value = _state.value.copy(
                busy = false,
                message = result.exceptionOrNull()?.message
            )
            refresh(jobId)
            result.getOrNull()?.let(onDone)
        }
    }

    fun forget(jobId: Long) {
        viewModelScope.launch {
            imports.forget(jobId)
            if (_state.value.openJobId == jobId) close()
        }
    }

    private suspend fun refresh(jobId: Long) {
        if (_state.value.openJobId != jobId) return
        _state.value = _state.value.copy(
            counts = runCatching { imports.counts(jobId) }.getOrNull(),
            items = runCatching { imports.itemsOnce(jobId) }.getOrDefault(emptyList()),
            job = runCatching { imports.jobOnce(jobId) }.getOrNull()
        )
    }

    override fun onCleared() {
        matching?.cancel()
        super.onCleared()
    }
}

/** True once the job has something worth committing. */
val ImportJobEntity.readyToCommit: Boolean
    get() = state == ImportState.REVIEW || state == ImportState.DONE
