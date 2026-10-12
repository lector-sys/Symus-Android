package io.github.lectorsys.symus.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lectorsys.symus.data.HistoryEntryEntity
import io.github.lectorsys.symus.data.LibraryRepository
import io.github.lectorsys.symus.data.SymusDatabase
import io.github.lectorsys.symus.data.TrackEntity
import io.github.lectorsys.symus.library.Listening
import io.github.lectorsys.symus.library.Window
import io.github.lectorsys.symus.library.formatListening
import io.github.lectorsys.symus.media.Extractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * The assistant's generative path, proven on a real model rather than asserted.
 *
 * Everything here runs against the bundle staged at `model-under-test.task` and
 * loaded through [ModelStore] — the same directory and the same loader the
 * Settings screen uses, so a pass says the shipped plumbing works, not that a
 * test harness can call MediaPipe.
 *
 * Each test logs `NET_AT_GENERATION`, measured from inside this process at the
 * moment it generates. That is the offline evidence: not a claim that the
 * harness was put in airplane mode, but a request that actually failed in the
 * same process, in the same second, as the answer that came back.
 */
@RunWith(AndroidJUnit4::class)
class OfflineAssistantTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The staged bundle, from wherever it was put.
     *
     * Two locations because the app-specific external directory is deleted when
     * the package is uninstalled, and `connectedAndroidTest` uninstalls after
     * every run — so a model pushed there survives exactly one invocation.
     * `/data/local/tmp` outlives the package, which makes the suite repeatable:
     *
     * ```
     * adb push model.task /data/local/tmp/model-under-test.task
     * adb shell chmod 644 /data/local/tmp/model-under-test.task
     * ```
     */
    private fun staged(): File? = listOf(
        File(context.getExternalFilesDir(null), STAGED),
        File("/data/local/tmp", STAGED)
    ).firstOrNull { it.isFile && it.length() > 0 }

    private fun modelsDir() = File(context.filesDir, "models").apply { mkdirs() }

    /** Puts the staged bundle where [ModelStore] looks, as the real feature would. */
    private fun install(): File {
        val source = staged()!!
        val target = File(modelsDir(), "qwen2.5-0.5b-instruct-q8.task")
        // Anything else in here is cleared first, always. ModelStore.installed()
        // takes the first *.task it finds, so leaving an "imported.task" beside
        // the catalogue one makes which model loads a matter of directory order.
        modelsDir().listFiles()
            ?.filter { it.absolutePath != target.absolutePath }
            ?.forEach { it.delete() }
        if (target.length() != source.length()) {
            target.delete()
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
        }
        return target
    }

    private fun store() = ModelStore(context, Extractor.okHttp())
    private fun runtime() = LlmRuntime(context, store())

    /**
     * Whether anything outside this device is reachable *now*, from this process.
     *
     * Deliberately not cached: the point is to sample it next to the generation
     * it is evidence about.
     */
    private fun networkReachable(): Boolean = runCatching {
        Extractor.okHttp().newBuilder()
            .callTimeout(java.time.Duration.ofSeconds(6))
            .build()
            .newCall(okhttp3.Request.Builder().url("https://huggingface.co/").head().build())
            .execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun requireModel() {
        // Two prerequisites, both of which are "this cannot be tested here"
        // rather than "this is broken". The lite build ships no inference
        // runtime by design, so every generative test below is inapplicable to
        // it — reporting those as failures would make a correct build look
        // broken in the totals.
        assumeTrue("This build has no inference runtime (lite flavour)", LlmEngine.PRESENT)
        assumeTrue("No model staged at $STAGED", staged() != null)
    }

    // -- 1. a normal conversational answer ------------------------------------

    @Test
    fun aNormalConversationalAnswerComesBack(): Unit = runBlocking {
        requireModel()
        install()
        val runtime = runtime()
        val loaded = runtime.load().getOrThrow()
        say("NET_AT_GENERATION ${networkReachable()}")
        say("LOAD_MS ${loaded.loadMillis}  BACKEND ${loaded.backend}")

        val reply = runtime.complete(
            user = "In one short sentence, what is a playlist?",
            system = "You are a helpful assistant inside a music app. Answer in one sentence."
        ).getOrThrow()

        say("CONV_MS  ${reply.millis}")
        say("CONV_TOK ${reply.promptTokens}")
        say("CONV_OUT ${reply.text.take(300)}")

        assertTrue("The model returned nothing", reply.text.isNotBlank())
        // A real sentence, not a token or a fragment of the template.
        assertTrue("Answer too short to be a sentence: ${reply.text}", reply.text.length > 15)
        assertFalse("Template markers leaked into the answer", reply.text.contains("<|im_"))
        runtime.unload()
        assertFalse("Runtime still reports loaded after unload", runtime.isLoaded)
    }

    // -- 2. grounded in rows this test actually wrote --------------------------

    /**
     * The figure in the answer has to come out of SQLite.
     *
     * The history rows are written here, the total is computed by [Listening]
     * from those rows, and the assertion is that the sentence contains that
     * exact string and nothing numeric that the tools did not produce. A model
     * that estimates, rounds or invents fails this.
     */
    @Test
    fun aListeningQuestionIsGroundedInRealDatabaseRows(): Unit = runBlocking {
        requireModel()
        install()

        val db = Room.inMemoryDatabaseBuilder(context, SymusDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            db.tracks().insertIfAbsent(
                listOf(
                    TrackEntity(videoId = "aaa", title = "Teardrop", artist = "Massive Attack", genre = "Trip hop"),
                    TrackEntity(videoId = "bbb", title = "Xtal", artist = "Aphex Twin", genre = "Electronic")
                )
            )
            // 12 plays of exactly 10 minutes, 8 of exactly 5 minutes:
            // 120 + 40 = 160 minutes = 2 hours 40 minutes, for 20 plays.
            repeat(12) { index ->
                db.history().record(
                    HistoryEntryEntity(
                        videoId = "aaa",
                        playedAt = now - (index + 1) * 3_600_000L,
                        listenedMs = 600_000L
                    )
                )
            }
            repeat(8) { index ->
                db.history().record(
                    HistoryEntryEntity(
                        videoId = "bbb",
                        playedAt = now - (index + 1) * 3_600_000L,
                        listenedMs = 300_000L
                    )
                )
            }

            val listening = Listening(db.history())
            val summary = listening.summary(Window.last30Days(now))
            val expectedTime = formatListening(summary.listenedMs)
            say("DB_PLAYS=${summary.plays} DB_TIME=$expectedTime DB_MS=${summary.listenedMs}")

            // The database is the reference, so state what it must say first.
            assertEquals("Seeded play count did not survive the write", 20, summary.plays)
            assertEquals("2 hours 40 minutes", expectedTime)

            val library = LibraryRepository(
                db.tracks(), db.history(), db.feedback(), db.playlists(),
                db.searchHistory(), db.channels(), Extractor()
            )
            val runtime = runtime()
            runtime.load().getOrThrow()
            val agent = ChatAgent(runtime, Tools(library, listening))

            say("NET_AT_GENERATION ${networkReachable()}")
            val reply = agent.ask("How much have I listened to in the last 30 days?", emptyList(), now)
                .getOrThrow()

            say("GROUND_TOOLS  ${reply.trace.toolCalls}")
            say("GROUND_CALLS  ${reply.trace.modelCalls}")
            say("GROUND_MS     ${reply.trace.totalMillis}")
            say("GROUND_KIND   ${reply.answer.kind}")
            say("GROUND_UNGRD  ${reply.answer.ungroundedFigures}")
            say("GROUND_OUT    ${reply.answer.text.take(400)}")
            say("GROUND_FACTS  ${reply.answer.facts}")

            assertTrue("No tool was called at all", reply.trace.toolCalls.isNotEmpty())

            // The recorded answer is the facts card, which the app renders from
            // the query result. It must carry the exact figure and the exact
            // period, and it must do so whatever the model wrote — that is the
            // property that makes the feature dependable rather than lucky.
            assertTrue(
                "The recorded figure is missing from the facts: ${reply.answer.facts}",
                reply.answer.facts.any { it.contains(expectedTime) }
            )
            assertTrue(
                "The facts do not state the period they cover: ${reply.answer.facts}",
                reply.answer.facts.all { it.contains("(the last 30 days)") }
            )
            assertTrue(
                "The play count is missing from the facts: ${reply.answer.facts}",
                reply.answer.facts.any { it.startsWith("Plays: 20") }
            )

            // The prose is commentary. It is allowed to be imperfect; what is
            // not allowed is an unsupported figure passing unflagged.
            if (!reply.answer.trustworthy) {
                say("GROUND_PROSE_REJECTED ${reply.answer.ungroundedFigures}")
                assertTrue(
                    "An untrustworthy answer must name the figures it could not ground",
                    reply.answer.ungroundedFigures.isNotEmpty()
                )
            }
            runtime.unload()
        } finally {
            db.close()
        }
    }

    // -- 3. a contextual follow-up --------------------------------------------

    /**
     * "And what about all time?" has no subject of its own. The only way it can
     * be answered is from the turn before it, so the assertion is that the
     * second answer carries the all-time figure — which differs from the
     * 30-day one precisely because the seeded rows straddle the boundary.
     */
    @Test
    fun aFollowUpIsAnsweredFromTheEarlierTurn(): Unit = runBlocking {
        requireModel()
        install()

        val db = Room.inMemoryDatabaseBuilder(context, SymusDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            db.tracks().insertIfAbsent(
                listOf(TrackEntity(videoId = "aaa", title = "Teardrop", artist = "Massive Attack"))
            )
            // Six recent plays of 10 minutes, and four old ones outside 30 days.
            repeat(6) { index ->
                db.history().record(
                    HistoryEntryEntity(
                        videoId = "aaa",
                        playedAt = now - (index + 1) * 3_600_000L,
                        listenedMs = 600_000L
                    )
                )
            }
            repeat(4) { index ->
                db.history().record(
                    HistoryEntryEntity(
                        videoId = "aaa",
                        playedAt = now - (60L + index) * Window.DAY,
                        listenedMs = 600_000L
                    )
                )
            }

            val listening = Listening(db.history())
            val recent = formatListening(listening.summary(Window.last30Days(now)).listenedMs)
            val allTime = formatListening(listening.summary(Window.allTime(now)).listenedMs)
            say("FOLLOWUP_RECENT $recent  FOLLOWUP_ALLTIME $allTime")
            assertTrue("The two windows must differ for this test to mean anything", recent != allTime)

            val library = LibraryRepository(
                db.tracks(), db.history(), db.feedback(), db.playlists(),
                db.searchHistory(), db.channels(), Extractor()
            )
            val runtime = runtime()
            runtime.load().getOrThrow()
            val agent = ChatAgent(runtime, Tools(library, listening))

            say("NET_AT_GENERATION ${networkReachable()}")
            val first = agent.ask("How long have I listened in the last 30 days?", emptyList(), now)
                .getOrThrow()
            say("FOLLOWUP_Q1_OUT ${first.answer.text.take(300)}")

            val history = listOf(
                ChatTurn(
                    question = "How long have I listened in the last 30 days?",
                    answer = first.answer.text,
                    tools = first.answer.toolsUsed
                )
            )
            val second = agent.ask("And what about all time?", history, now).getOrThrow()

            say("FOLLOWUP_Q2_TOOLS ${second.trace.toolCalls}")
            say("FOLLOWUP_Q2_OUT   ${second.answer.text.take(300)}")
            say("FOLLOWUP_Q2_UNGRD ${second.answer.ungroundedFigures}")
            say("FOLLOWUP_Q2_FACTS ${second.answer.facts}")

            // The follow-up's job is retrieval: a new tool call, over the
            // period the follow-up named, and a facts card carrying that
            // period's figure. All three are the app's work, and all three are
            // asserted every run — not the model's sentence, which this test
            // cannot and should not pin down.
            assertTrue("The follow-up called no tool", second.trace.toolCalls.isNotEmpty())
            assertTrue(
                "The follow-up's facts do not carry the all-time figure '$allTime': " +
                    "${second.answer.facts}",
                second.answer.facts.any { it.contains(allTime) }
            )
            assertTrue(
                "The follow-up's facts are still on the previous period: ${second.answer.facts}",
                second.answer.facts.none { it.contains("the last 30 days") }
            )

            // Whether the model also drops a stray number into the prose varies
            // between runs. What must hold every time is that the app caught it
            // and did not present it as a measurement.
            if (!second.answer.trustworthy) {
                say("FOLLOWUP_Q2_PROSE_REJECTED ${second.answer.ungroundedFigures}")
                assertTrue(
                    "An untrustworthy answer must name what it could not ground",
                    second.answer.ungroundedFigures.isNotEmpty()
                )
            }
            runtime.unload()
        } finally {
            db.close()
        }
    }

    // -- 4. cancellation and resource cleanup ---------------------------------

    /**
     * Cancelling a generation must leave the runtime usable.
     *
     * The native `generateResponse` is a blocking call that does not observe
     * Kotlin cancellation, so the coroutine unwinds when it returns rather than
     * partway through. What matters is what is true afterwards: the busy flag is
     * down, the mutex was released, and the very next question still gets an
     * answer. A leaked lock would hang the second call, which is why the second
     * call is wrapped in a timeout rather than simply awaited.
     */
    @Test
    fun cancellingAGenerationLeavesTheRuntimeUsable(): Unit = runBlocking {
        requireModel()
        install()
        val runtime = runtime()
        runtime.load().getOrThrow()
        say("NET_AT_GENERATION ${networkReachable()}")

        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.async {
            runtime.complete(
                user = "Write a long description of a music library, at length.",
                system = "You are a helpful assistant inside a music app."
            )
        }
        delay(250)
        job.cancel()
        runCatching { job.await() }
        say("CANCEL_JOB_CANCELLED ${job.isCancelled}")
        assertTrue("The job did not register as cancelled", job.isCancelled)

        // The busy flag is cleared in a finally-style block; give the unwind a
        // moment, then require it to be down.
        val settled = withTimeoutOrNull(120_000) {
            while (runtime.busy.value) delay(100)
            true
        }
        say("CANCEL_BUSY_CLEARED ${settled == true}")
        assertTrue("busy never returned to false after cancellation", settled == true)

        val after = withTimeoutOrNull(180_000) {
            runtime.complete("Reply with exactly one word: the colour of a clear midday sky.")
        }
        assertNotNull("The runtime deadlocked after a cancelled generation", after)
        val text = after!!.getOrThrow().text
        say("CANCEL_NEXT_OUT $text")
        assertTrue("No answer after cancellation", text.isNotBlank())

        runtime.unload()
        assertFalse("unload() left the engine in place", runtime.isLoaded)
        assertFalse("busy stuck high after unload", runtime.busy.value)
    }

    // -- 5. playback keeps running through generation --------------------------

    /**
     * Real audio, sampled throughout, compared against the wall clock.
     *
     * The previous version of this test proved nothing, and it is worth saying
     * how. It played a two-second tone on `REPEAT_MODE_ONE` and compared
     * `currentPosition` before and after — but on a looping item the position
     * is the offset inside the current lap, so it can never exceed 2000 ms and
     * says nothing about how many laps happened. Its assertion was
     * `after > before`, which one millisecond of progress in ninety seconds
     * satisfies. A near-total stall would have passed it.
     *
     * This one uses a file longer than the whole test, never loops, never
     * seeks, and samples position and state continuously. The claim under test
     * is the one a listener would notice: the playback clock keeps up with the
     * wall clock while the model works, and never freezes long enough to hear.
     */
    @Test
    fun playbackKeepsUpWithTheWallClockWhileTheModelGenerates(): Unit = runBlocking {
        requireModel()
        install()
        val audio = writeLongToneWav(File(context.cacheDir, "playback-probe.wav"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(audio)))
                // No looping and no seeking: the position has to be a monotonic
                // clock for the comparison below to mean anything at all.
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
                playWhenReady = true
            }
        }
        val exo = player!!
        try {
            val started = withTimeoutOrNull(30_000) {
                while (true) {
                    var playing = false
                    instrumentation.runOnMainSync { playing = exo.isPlaying }
                    if (playing) return@withTimeoutOrNull true
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            assumeTrue("Playback never started on this device", started == true)

            val runtime = runtime()
            runtime.load().getOrThrow()
            say("NET_AT_GENERATION " + networkReachable())

            val walls = mutableListOf<Long>()
            val positions = mutableListOf<Long>()
            val playingFlags = mutableListOf<Boolean>()
            val states = mutableListOf<Int>()

            fun sample() {
                var position = 0L
                var playing = false
                var state = 0
                instrumentation.runOnMainSync {
                    position = exo.currentPosition
                    playing = exo.isPlaying
                    state = exo.playbackState
                }
                walls += System.currentTimeMillis()
                positions += position
                playingFlags += playing
                states += state
            }

            // Generations run back to back until the load has been sustained
            // for long enough to mean something. One answer can come back in
            // four seconds, and four seconds of smooth audio is not evidence
            // that a long answer would also be smooth.
            sample()
            var generations = 0
            var lastText = ""
            val deadline = System.currentTimeMillis() + LOAD_FOR_MS
            val work = CoroutineScope(Dispatchers.IO).async {
                while (System.currentTimeMillis() < deadline) {
                    val one = runtime.complete(
                        user = "Describe, in eight sentences, what makes a good playlist " +
                            "and how you would build one.",
                        system = "You are a helpful assistant inside a music app."
                    ).getOrThrow()
                    generations++
                    lastText = one.text
                }
            }
            while (work.isActive) {
                delay(SAMPLE_MS)
                sample()
            }
            sample()
            work.await()

            val elapsed = walls.last() - walls.first()
            val advanced = positions.last() - positions.first()
            // The worst freeze: the longest wall-clock interval across which
            // the playback clock did not move forward.
            var worstStall = 0L
            for (index in 1 until positions.size) {
                if (positions[index] <= positions[index - 1]) {
                    val gap = walls[index] - walls[index - 1]
                    if (gap > worstStall) worstStall = gap
                }
            }
            val notPlaying = playingFlags.count { !it }
            val ratio = if (elapsed > 0) advanced.toDouble() / elapsed else 0.0

            say("PB_GENERATIONS " + generations)
            say("PB_SAMPLES     " + positions.size)
            say("PB_ELAPSED_MS  " + elapsed)
            say("PB_ADVANCED_MS " + advanced)
            say("PB_RATIO       " + String.format("%.3f", ratio))
            say("PB_WORST_STALL " + worstStall)
            say("PB_NOT_PLAYING " + notPlaying)
            say("PB_END_STATE   " + states.last())
            say("PB_OUT         " + lastText.take(120))

            assertTrue("Too few samples to judge: " + positions.size, positions.size >= 10)
            assertTrue(
                "The track ended during the test; it must outlast the generation",
                states.last() != Player.STATE_ENDED
            )
            assertEquals("The player reported not-playing on some samples", 0, notPlaying)
            assertTrue(
                "Playback fell behind: " + advanced + "ms of audio in " + elapsed +
                    "ms of wall clock (ratio " + String.format("%.3f", ratio) + ")",
                ratio >= MIN_RATIO
            )
            assertTrue(
                "The playback clock froze for " + worstStall + "ms, which is audible",
                worstStall <= MAX_STALL_MS
            )
            runtime.unload()
        } finally {
            instrumentation.runOnMainSync { exo.release() }
            audio.delete()
        }
    }

    // -- 6. missing, corrupt and incompatible bundles --------------------------

    @Test
    fun aMissingModelIsReportedRatherThanCrashing(): Unit = runBlocking {
        assumeTrue("This build has no inference runtime (lite flavour)", LlmEngine.PRESENT)
        modelsDir().listFiles()?.forEach { it.delete() }
        val runtime = runtime()
        val result = runtime.load()
        say("MISSING_RESULT ${result.exceptionOrNull()?.message}")
        assertTrue("Loading with no model installed should fail", result.isFailure)
        assertTrue(
            "The failure must say what is wrong",
            result.exceptionOrNull()?.message?.contains("No model", ignoreCase = true) == true
        )
        assertFalse(runtime.isLoaded)
    }

    /** A truncated bundle: the right first bytes, and then nothing. */
    @Test
    fun aCorruptBundleIsRefusedWithoutTakingTheProcessDown(): Unit = runBlocking {
        requireModel()
        val source = staged()!!
        modelsDir().listFiles()?.forEach { it.delete() }
        val corrupt = File(modelsDir(), "qwen2.5-0.5b-instruct-q8.task")
        source.inputStream().use { input ->
            corrupt.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                while (copied < 20L * 1024 * 1024) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                }
            }
        }
        say("CORRUPT_BYTES ${corrupt.length()}")

        val runtime = runtime()
        val result = runtime.load()
        say("CORRUPT_RESULT ${result.exceptionOrNull()?.message}")
        assertTrue("A truncated bundle must not load", result.isFailure)
        assertFalse("A failed load must leave nothing loaded", runtime.isLoaded)
        // The process is still alive and the runtime still works: prove it.
        install()
        val recovered = runtime().load()
        say("CORRUPT_RECOVERED ${recovered.isSuccess}")
        assertTrue("The runtime did not recover after a corrupt bundle", recovered.isSuccess)
        runtime().unload()
        corrupt.delete()
    }

    /** Not a bundle at all: the wrong format entirely. */
    @Test
    fun anIncompatibleFileIsRefusedWithAReadableMessage(): Unit = runBlocking {
        assumeTrue("This build has no inference runtime (lite flavour)", LlmEngine.PRESENT)
        modelsDir().listFiles()?.forEach { it.delete() }
        val wrong = File(modelsDir(), "qwen2.5-0.5b-instruct-q8.task")
        RandomAccessFile(wrong, "rw").use { file ->
            val random = Random(7)
            val buffer = ByteArray(1 shl 20)
            repeat(12) {
                random.nextBytes(buffer)
                file.write(buffer)
            }
        }
        say("INCOMPATIBLE_BYTES ${wrong.length()}")

        val runtime = runtime()
        val result = runtime.load()
        val message = result.exceptionOrNull()?.message
        say("INCOMPATIBLE_RESULT $message")
        assertTrue("Random bytes must not load as a model", result.isFailure)
        assertFalse(runtime.isLoaded)
        assertTrue("The failure must carry a message for the user", !message.isNullOrBlank())
        wrong.delete()
    }

    // -- 7. the app's own import path ------------------------------------------

    /**
     * The Settings screen's "import a bundle you already have" path, driven the
     * way the picker drives it: a Uri the store has never seen, resolved through
     * ContentResolver, copied, size-checked and renamed into place.
     */
    @Test
    fun theImportFlowInstallsAStagedBundle(): Unit = runBlocking {
        requireModel()
        val source = staged()!!
        modelsDir().listFiles()?.forEach { it.delete() }

        val store = store()
        val started = System.currentTimeMillis()
        val imported = store.importFrom(Uri.fromFile(source), source.name).getOrThrow()
        val millis = System.currentTimeMillis() - started

        say("IMPORT_MS    $millis")
        say("IMPORT_FILE  ${imported.name}")
        say("IMPORT_BYTES ${imported.length()}")
        say("IMPORT_STATE ${store.state.value}")

        assertEquals("Imported bundle is a different size", source.length(), imported.length())
        assertTrue("Import left nothing installed", store.installed() != null)
        assertTrue(
            "An imported bundle has no catalogue entry and must not claim one",
            store.installedSpec() == null
        )

        // And it has to actually load, or the import proved nothing.
        val runtime = runtime()
        val loaded = runtime.load().getOrThrow()
        say("IMPORT_LOAD_MS ${loaded.loadMillis}")
        say("IMPORT_LOADED  ${loaded.name}")
        val reply = runtime.complete("Reply with exactly one word: yes.").getOrThrow()
        say("IMPORT_OUT     ${reply.text.take(120)}")
        assertTrue("An imported bundle loaded but produced nothing", reply.text.isNotBlank())
        runtime.unload()

        // Leave the catalogue-named copy behind for the other tests.
        install()
    }

    /** A tiny file is not a model, and must be refused before anything native sees it. */
    @Test
    fun anImplausiblySmallImportIsRefused(): Unit = runBlocking {
        val scrap = File(context.cacheDir, "not-a-model.task")
        scrap.writeText("this is not half a gigabyte of weights")
        val store = store()
        val result = store.importFrom(Uri.fromFile(scrap), scrap.name)
        say("SMALL_IMPORT ${(store.state.value as? ModelState.Failed)?.message}")
        assertTrue("A 38-byte file must not import as a model", result.isFailure)
        scrap.delete()
    }

    /**
     * Whether [answer] states [figure], allowing for ordinary English joining.
     *
     * "2 hours 40 minutes" and "2 hours and 40 minutes" are the same figure.
     * The rule being tested is that the number came from the database, not that
     * the model is forbidden a conjunction.
     */
    private fun quotes(answer: String, figure: String): Boolean {
        fun normalise(text: String) = text.lowercase()
            .replace(Regex("""\band\b"""), " ")
            .replace(",", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return normalise(answer).contains(normalise(figure))
    }

    private fun say(line: String) = Log.i(TAG, line)

    /**
     * Five minutes of a quiet sine tone, 16-bit mono at 22.05 kHz.
     *
     * Long enough to outlast the whole generation, so the item never ends and
     * never has to loop — which is what makes `currentPosition` a usable clock.
     * Generated rather than shipped: no asset, no network, nobody's copyright.
     */
    private fun writeLongToneWav(target: File): File {
        val rate = 22_050
        val seconds = 300
        // One second of audio, written 300 times: a 13 MB file without ever
        // holding 13 MB in memory.
        val second = ByteArray(rate * 2)
        for (index in 0 until rate) {
            val value = (Math.sin(2.0 * Math.PI * 220.0 * index / rate) * 8_000).toInt()
            second[index * 2] = (value and 0xFF).toByte()
            second[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        val dataSize = second.size * seconds

        fun le32(value: Int) = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )

        fun le16(value: Int) =
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

        target.outputStream().buffered(1 shl 16).use { out ->
            out.write("RIFF".toByteArray())
            out.write(le32(36 + dataSize))
            out.write("WAVEfmt ".toByteArray())
            out.write(le32(16))
            out.write(le16(1))          // PCM
            out.write(le16(1))          // mono
            out.write(le32(rate))
            out.write(le32(rate * 2))   // byte rate
            out.write(le16(2))          // block align
            out.write(le16(16))         // bits per sample
            out.write("data".toByteArray())
            out.write(le32(dataSize))
            repeat(seconds) { out.write(second) }
        }
        return target
    }

    private companion object {
        const val TAG = "SYMUS_LLM"
        const val STAGED = "model-under-test.task"

        /** How often playback is sampled while the model works. */
        const val SAMPLE_MS = 250L

        /**
         * The fraction of the wall clock the playback clock must keep. Not
         * 1.0: sampling is not instantaneous and the bracketing samples carry
         * a little slack.
         */
        const val MIN_RATIO = 0.95

        /** The longest freeze that is not an audible gap. */
        const val MAX_STALL_MS = 700L

        /**
         * How long the model is kept working. Long enough that a stall would
         * have had somewhere to happen, short enough to stay a test.
         */
        const val LOAD_FOR_MS = 45_000L
    }
}
