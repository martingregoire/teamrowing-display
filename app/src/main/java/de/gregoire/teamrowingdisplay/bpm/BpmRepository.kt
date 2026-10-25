package de.gregoire.teamrowingdisplay.bpm

import android.content.Context
import android.util.AtomicFile
import de.gregoire.teamrowingdisplay.spotify.Track
import de.gregoire.teamrowingdisplay.sync.BpmSyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * BPM per track URI: detected values and the trainer's corrections, stored as a JSON file and
 * synced with the remote app. A track has an entry once it was looked up, even if no BPM was found,
 * so it isn't looked up again.
 */
class BpmRepository(context: Context, private val lookup: BpmLookup) : BpmSyncStore {

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val entriesMutex = Mutex()
    private val lookupMutex = Mutex()
    private var loaded = false
    private var lastFailedLookupAt = 0L

    private val _entries = MutableStateFlow<Map<String, BpmEntry>>(emptyMap())
    val entries: StateFlow<Map<String, BpmEntry>> = _entries.asStateFlow()

    private val _localChanges = MutableSharedFlow<Map<String, BpmEntry>>(extraBufferCapacity = 64)
    override val localChanges: SharedFlow<Map<String, BpmEntry>> = _localChanges.asSharedFlow()

    /**
     * Looks up the tracks that have no entry yet. Returns false if a service failed;
     * the lookup is then retried at the earliest after [RETRY_DELAY_MS].
     */
    suspend fun lookUpMissing(tracks: List<Track>): Boolean = lookupMutex.withLock {
        entriesMutex.withLock { loadIfNeeded() }
        val missing = tracks.filter { it.uri !in _entries.value }.distinctBy { it.uri }
        if (missing.isEmpty()) return true
        if (System.currentTimeMillis() < lastFailedLookupAt + RETRY_DELAY_MS) return false

        val result = lookup.lookUp(missing)
        if (!result.complete) lastFailedLookupAt = System.currentTimeMillis()
        val newEntries = missing.mapNotNull { track ->
            val detected = result.found[track.uri]
            when {
                detected != null -> track.uri to BpmEntry(detected.bpm, detected.source)
                result.complete -> track.uri to BpmEntry()
                else -> null
            }
        }
        // Merge, as the remote app may have sent entries for these tracks during the lookup
        update { entries -> entries + newEntries.map { (uri, entry) -> uri to (entries[uri]?.mergedWith(entry) ?: entry) } }
        result.complete
    }

    /** Sets the trainer's value; null, or the detected value itself, removes the correction. */
    suspend fun setManualBpm(uri: String, bpm: Double?) = edit(uri) { it.withManualBpm(bpm) }

    /** Multiplies the current value and rounds it, e.g. by 2 or 0.5 to fix double or half tempo. */
    suspend fun multiplyBpm(uri: String, factor: Double) = edit(uri) { entry -> entry.adjusted { it * factor } }

    /** Adds to the current value and rounds it, e.g. +1 or -1 for fine-tuning. */
    suspend fun addBpm(uri: String, delta: Double) = edit(uri) { entry -> entry.adjusted { it + delta } }

    suspend fun setHidden(uri: String, hidden: Boolean) = edit(uri) { it.copy(hidden = hidden) }

    override suspend fun snapshot(): Map<String, BpmEntry> = entriesMutex.withLock {
        loadIfNeeded()
        _entries.value
    }

    override suspend fun merge(incoming: Map<String, BpmEntry>) = update(fromSync = true) { entries ->
        entries + incoming.map { (uri, entry) -> uri to (entries[uri]?.mergedWith(entry) ?: entry) }
    }

    /** Applies a change by the trainer, stamping it with the time for merging with the other app. */
    private suspend fun edit(uri: String, change: (BpmEntry) -> BpmEntry) = update { entries ->
        val entry = entries[uri] ?: BpmEntry()
        val changed = change(entry)
        if (changed == entry) entries else entries + (uri to changed.copy(editedAt = System.currentTimeMillis()))
    }

    private suspend fun update(
        fromSync: Boolean = false,
        transform: (Map<String, BpmEntry>) -> Map<String, BpmEntry>,
    ) = entriesMutex.withLock {
        loadIfNeeded()
        val previous = _entries.value
        val updated = transform(previous)
        if (updated == previous) return@withLock
        _entries.value = updated
        withContext(Dispatchers.IO) { save(updated) }
        if (!fromSync) {
            _localChanges.tryEmit(updated.filter { (uri, entry) -> previous[uri] != entry })
        }
    }

    /** Reads the stored entries on first use. Must be called with [entriesMutex] held. */
    private suspend fun loadIfNeeded() {
        if (loaded) return
        _entries.value = withContext(Dispatchers.IO) { load() }
        loaded = true
    }

    private fun load(): Map<String, BpmEntry> {
        if (!file.baseFile.exists()) return emptyMap()
        return bpmEntriesFromJson(JSONObject(file.readFully().decodeToString()))
    }

    private fun save(entries: Map<String, BpmEntry>) {
        val stream = file.startWrite()
        try {
            stream.write(bpmEntriesToJson(entries).toString().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private companion object {
        const val FILE_NAME = "bpm.json"
        const val RETRY_DELAY_MS = 60_000L
    }
}
