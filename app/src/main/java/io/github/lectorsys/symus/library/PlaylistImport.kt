package io.github.lectorsys.symus.library

import io.github.lectorsys.symus.data.ImportDao
import io.github.lectorsys.symus.data.ImportItemEntity
import io.github.lectorsys.symus.data.ImportJobEntity
import io.github.lectorsys.symus.data.ImportState
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.MatchState
import io.github.lectorsys.symus.media.Extractor
import io.github.lectorsys.symus.media.SearchHit
import io.github.lectorsys.symus.media.SearchKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Importing a playlist from somewhere else, one resumable step at a time.
 *
 * The shape of this is dictated by what actually goes wrong. A five-hundred
 * track playlist is five hundred searches; the process will be killed partway
 * through at least once, the network will drop, and YouTube will rate-limit.
 * So nothing is held in memory: every fetched row and every match is written
 * as it happens, and [resume] picks up from whatever state the tables are in.
 *
 * The user is never shown a finished playlist they did not approve. Fetching
 * and matching leave the job in REVIEW; only [commit] creates the playlist,
 * and only the matches still standing at that point go into it.
 */
@Singleton
class PlaylistImport @Inject constructor(
    private val imports: ImportDao,
    private val extractor: Extractor,
    private val library: LibraryRepository,
    private val spotify: SpotifyAdapter,
    private val apple: AppleAdapter
) {

    /** The adapter for a provider, or null where the source is not a web API. */
    private fun adapterFor(provider: Provider): ProviderAdapter? = when (provider) {
        Provider.SPOTIFY -> spotify
        Provider.APPLE -> apple
        Provider.YOUTUBE, Provider.FILE -> null
    }

    /** Where each provider stands right now, for the Import screen to show. */
    suspend fun access(provider: Provider): ProviderAccess =
        adapterFor(provider)?.access() ?: ProviderAccess.Ready()

    val jobs: Flow<List<ImportJobEntity>> = imports.jobsFlow()

    fun job(id: Long) = imports.jobFlow(id)

    fun items(id: Long) = imports.itemsFlow(id)

    /**
     * Starts, or re-opens, an import of [link].
     *
     * Idempotent by (provider, sourceId): pasting the same link twice returns
     * to the job already in progress instead of starting a second one and
     * duplicating every row. Corrections the user made last time survive,
     * because [ImportItemEntity.userEdited] rows are never re-matched.
     */
    suspend fun startLink(link: SourceLink): Long = withContext(Dispatchers.IO) {
        val existing = imports.findJob(link.provider.id, link.sourceId)
        if (existing != null) return@withContext existing.id

        imports.insertJob(
            ImportJobEntity(
                provider = link.provider.id,
                sourceId = link.sourceId,
                sourceUrl = link.url,
                title = "Imported playlist",
                state = ImportState.PENDING
            )
        )
    }

    /**
     * Starts an import from a file the user picked.
     *
     * The file's own name is the source id, so re-importing the same export
     * updates the same job rather than stacking up duplicates.
     */
    suspend fun startFile(
        fileName: String,
        content: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        val tracks = parsePlaylistFile(fileName, content)
        if (tracks.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException(
                    "Nothing recognisable in $fileName. A CSV export, an .m3u, or one " +
                        "\"Artist - Title\" per line all work."
                )
            )
        }

        val existing = imports.findJob(Provider.FILE.id, fileName)
        val jobId = existing?.id ?: imports.insertJob(
            ImportJobEntity(
                provider = Provider.FILE.id,
                sourceId = fileName,
                title = fileName.substringBeforeLast('.'),
                state = ImportState.PENDING
            )
        )

        if (existing == null) {
            imports.insertItems(tracks.map { it.toEntity(jobId) })
        }
        imports.setJobProgress(jobId, total = tracks.size, fetched = tracks.size, token = null)
        imports.setJobState(jobId, ImportState.MATCHING, error = null)
        Result.success(jobId)
    }

    /**
     * Pulls the source's track list in, if it has not been pulled already.
     *
     * For YouTube this is one page — NewPipe hands back the whole playlist —
     * and the row count is written before any matching starts so progress is
     * meaningful from the first second rather than counting up from zero with
     * no denominator.
     */
    suspend fun fetch(jobId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val job = imports.job(jobId) ?: return@withContext Result.failure(
            IllegalStateException("That import is gone.")
        )
        val provider = Provider.of(job.provider)

        if (imports.itemCount(jobId) > 0) {
            imports.setJobState(jobId, ImportState.MATCHING, error = null)
            return@withContext Result.success(Unit)
        }

        imports.setJobState(jobId, ImportState.FETCHING, error = null)
        val url = job.sourceUrl ?: return@withContext Result.failure(
            IllegalStateException("That import has no link to fetch.")
        )

        adapterFor(provider)?.let { adapter ->
            return@withContext fetchFromAdapter(jobId, job.sourceId, adapter)
        }

        return@withContext runCatching {
            val resolved = extractor.playlistTracks(url, limit = MAX_TRACKS)
            if (resolved.hits.isEmpty()) error("That playlist came back empty.")

            val tracks = resolved.hits.mapIndexed { index, hit ->
                SourceTrack(
                    position = index,
                    title = hit.track.title,
                    artist = hit.track.artist,
                    durationSec = hit.track.duration
                )
            }
            imports.insertItems(tracks.map { it.toEntity(jobId) })
            resolved.name?.takeIf { it.isNotBlank() }?.let { imports.renameJob(jobId, it) }
            imports.setJobProgress(jobId, tracks.size, tracks.size, null)

            // A YouTube import already *is* YouTube ids: every row resolves to
            // itself, exactly, with no searching and no ambiguity. Writing the
            // matches here rather than sending them round the matcher is both
            // correct and the difference between instant and five minutes.
            val items = imports.items(jobId)
            items.forEachIndexed { index, item ->
                val hit = resolved.hits.getOrNull(index) ?: return@forEachIndexed
                library.remember(listOf(hit))
                imports.setMatch(item.id, hit.track.videoId, 1.0, MatchState.EXACT)
            }
            imports.setJobState(jobId, ImportState.REVIEW, error = null)
            Unit
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            imports.setJobState(
                jobId,
                ImportState.FAILED,
                failure.message ?: "That playlist could not be read."
            )
        }
    }

    /**
     * Pulls every page of a provider playlist in, then hands it to the matcher.
     *
     * Every page is written before the next is asked for, so a rate limit or a
     * dead connection halfway through a 900-track playlist leaves the rows that
     * arrived rather than nothing. Position is the source's own ordering and is
     * carried through untouched — a playlist that imports in a different order
     * is not the playlist the user asked for.
     *
     * Unlike YouTube, nothing here resolves to a video id on its own. These
     * rows are titles and ISRCs, and every one of them goes through the matcher
     * and the review screen before anything is added.
     */
    private suspend fun fetchFromAdapter(
        jobId: Long,
        sourceId: String,
        adapter: ProviderAdapter
    ): Result<Unit> = runCatching {
        when (val state = adapter.access()) {
            is ProviderAccess.Ready -> Unit
            is ProviderAccess.NeedsConfiguration ->
                error("${adapter.provider.label} needs ${state.what}. ${state.how}")
            is ProviderAccess.NeedsSignIn -> error(state.why)
            is ProviderAccess.Restricted -> error("${state.reason} ${state.remedy}")
            is ProviderAccess.Failed -> error(state.message)
        }

        var page: String? = null
        var collected = 0
        var pages = 0
        var title: String? = null

        do {
            val result = adapter.page(sourceId, page).getOrThrow()
            if (title == null) title = result.title
            if (result.tracks.isNotEmpty()) {
                // Positions are re-based on what has already been stored rather
                // than trusting each page to number itself from zero.
                imports.insertItems(
                    result.tracks.mapIndexed { index, track ->
                        track.copy(position = collected + index).toEntity(jobId)
                    }
                )
                collected += result.tracks.size
            }
            imports.setJobProgress(
                jobId,
                total = maxOf(result.totalTracks, collected),
                fetched = collected,
                token = result.nextPage
            )
            page = result.nextPage
            pages++
        } while (page != null && collected < MAX_TRACKS && pages < MAX_PAGES)

        if (collected == 0) error("That playlist came back empty.")
        title?.takeIf { it.isNotBlank() }?.let { imports.renameJob(jobId, it) }
        imports.setJobState(jobId, ImportState.MATCHING, error = null)
        Unit
    }.onFailure { failure ->
        if (failure is CancellationException) throw failure
        imports.setJobState(
            jobId,
            ImportState.FAILED,
            failure.message ?: "That playlist could not be read."
        )
    }

    /**
     * Matches the rows that still have no candidate.
     *
     * Works in bounded batches and writes each result as it goes, so a process
     * death loses at most the row in flight. Call it again and it carries on:
     * [ImportDao.pendingItems] only ever returns work that has not been done.
     */
    suspend fun matchNext(jobId: Long, batch: Int = 12): Int = withContext(Dispatchers.IO) {
        imports.setJobState(jobId, ImportState.MATCHING, error = null)
        val pending = imports.pendingItems(jobId, batch)
        if (pending.isEmpty()) {
            imports.setJobState(jobId, ImportState.REVIEW, error = null)
            return@withContext 0
        }

        for (item in pending) {
            val source = item.toSource()
            val hits = runCatching {
                extractor.search(Matching.queryFor(source), SearchKind.SONGS, limit = 8).hits
            }.getOrDefault(emptyList<SearchHit>())

            val match = Matching.best(source, hits)
            if (match == null) {
                // Kept, not discarded. An unresolved row is the honest record
                // that this track exists in the source and was not found here,
                // and the review screen can offer it for a manual search later.
                imports.setMatch(item.id, null, 0.0, MatchState.UNRESOLVED)
            } else {
                hits.firstOrNull { it.track.videoId == match.videoId }
                    ?.let { library.remember(listOf(it)) }
                imports.setMatch(item.id, match.videoId, match.confidence, match.state)
            }
        }

        val stillPending = imports.countInState(jobId, MatchState.PENDING)
        if (stillPending == 0) imports.setJobState(jobId, ImportState.REVIEW, error = null)
        pending.size
    }

    /** The user picked a different track for a row, or cleared it. */
    suspend fun chooseMatch(itemId: Long, videoId: String?) = withContext(Dispatchers.IO) {
        imports.setUserMatch(
            itemId,
            videoId,
            confidence = if (videoId == null) 0.0 else 1.0,
            state = if (videoId == null) MatchState.REJECTED else MatchState.EXACT
        )
    }

    /**
     * Creates the playlist from the matches that survived review.
     *
     * Rows with no match are left behind in the job rather than dropped, so
     * the record of what could not be found outlives the import and the user
     * can come back to it.
     */
    suspend fun commit(jobId: Long): Result<Long> = withContext(Dispatchers.IO) {
        val job = imports.job(jobId) ?: return@withContext Result.failure(
            IllegalStateException("That import is gone.")
        )
        imports.setJobState(jobId, ImportState.IMPORTING, error = null)

        val matched = imports.items(jobId)
            .sortedBy { it.position }
            .mapNotNull { item ->
                item.matchedVideoId?.takeIf { item.matchState != MatchState.REJECTED }
            }

        // A match can only be added if the track behind it actually exists.
        // Everything the matcher chooses is remembered as it goes, but a match
        // chosen by hand comes from wherever the screen found it, and adding an
        // id with no row is a foreign key violation that fails the whole commit
        // — losing an import the user has just spent time reviewing. Dropping
        // the orphans and saying how many is the lesser harm.
        val known = library.knownTrackIds(matched).toSet()
        val ordered = matched.filter { it in known }
        val dropped = matched.size - ordered.size

        if (ordered.isEmpty()) {
            imports.setJobState(jobId, ImportState.REVIEW, "Nothing matched, so nothing to add.")
            return@withContext Result.failure(
                IllegalStateException("Nothing matched, so there is nothing to add yet.")
            )
        }

        val playlistId = job.playlistId ?: library.createPlaylist(
            title = job.title,
            sourceProvider = job.provider,
            sourceId = job.sourceId,
            sourceUrl = job.sourceUrl
        )
        library.addToPlaylist(playlistId, ordered)
        imports.setJobPlaylist(jobId, playlistId)
        imports.setJobState(
            jobId,
            ImportState.DONE,
            error = if (dropped > 0) {
                "$dropped ${if (dropped == 1) "track was" else "tracks were"} skipped: " +
                    "the matched track is no longer in the library."
            } else null
        )
        Result.success(playlistId)
    }

    suspend fun retryUnresolved(jobId: Long) = withContext(Dispatchers.IO) {
        imports.requeueUnresolved(jobId)
        imports.setJobState(jobId, ImportState.MATCHING, error = null)
    }

    suspend fun cancel(jobId: Long) = withContext(Dispatchers.IO) {
        imports.setJobState(jobId, ImportState.CANCELLED, error = null)
    }

    suspend fun forget(jobId: Long) = withContext(Dispatchers.IO) { imports.deleteJob(jobId) }

    /** A one-shot read, for a screen that refreshes rather than observes. */
    suspend fun itemsOnce(jobId: Long): List<ImportItemEntity> =
        withContext(Dispatchers.IO) { imports.items(jobId) }

    suspend fun jobOnce(jobId: Long): ImportJobEntity? =
        withContext(Dispatchers.IO) { imports.job(jobId) }

    suspend fun counts(jobId: Long): ImportCounts = withContext(Dispatchers.IO) {
        ImportCounts(
            total = imports.itemCount(jobId),
            pending = imports.countInState(jobId, MatchState.PENDING),
            exact = imports.countInState(jobId, MatchState.EXACT),
            likely = imports.countInState(jobId, MatchState.LIKELY),
            uncertain = imports.countInState(jobId, MatchState.UNCERTAIN),
            unresolved = imports.countInState(jobId, MatchState.UNRESOLVED),
            rejected = imports.countInState(jobId, MatchState.REJECTED)
        )
    }

    private companion object {
        /**
         * The ceiling on one import.
         *
         * Not a technical limit — a kindness. Past this the review screen is
         * unusable and the searches take long enough that nobody waits.
         */
        const val MAX_TRACKS = 500

        /**
         * A stop on pagination independent of the track cap. A provider that
         * keeps handing back a `next` for an empty page would otherwise loop
         * until the battery ran out.
         */
        const val MAX_PAGES = 40
    }
}

/** How an import is doing, in the terms the review screen groups by. */
data class ImportCounts(
    val total: Int,
    val pending: Int,
    val exact: Int,
    val likely: Int,
    val uncertain: Int,
    val unresolved: Int,
    val rejected: Int
) {
    val matched: Int get() = exact + likely + uncertain
    val needsAttention: Int get() = uncertain + unresolved
    val done: Int get() = total - pending
}

private fun SourceTrack.toEntity(jobId: Long) = ImportItemEntity(
    jobId = jobId,
    position = position,
    sourceTrackId = sourceTrackId,
    isrc = isrc,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec
)

private fun ImportItemEntity.toSource() = SourceTrack(
    position = position,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    isrc = isrc,
    sourceTrackId = sourceTrackId
)
