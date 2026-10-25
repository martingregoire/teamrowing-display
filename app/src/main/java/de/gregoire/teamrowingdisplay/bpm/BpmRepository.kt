package de.gregoire.teamrowingdisplay.bpm

import android.content.Context
import android.util.AtomicFile
import de.gregoire.teamrowingdisplay.spotify.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DecimalFormat
import kotlin.math.roundToLong

data class BpmEntry(
    /** BPM from a lookup service, or null if no service knows the track. */
    val detectedBpm: Double? = null,
    val source: BpmSource? = null,
    /** The trainer's value, which replaces the detected one. */
    val manualBpm: Double? = null,
    /** Set by the trainer for tracks shown without tempo, e.g. for stretching. */
    val hidden: Boolean = false,
) {
    val bpm: Double?
        get() = manualBpm ?: detectedBpm
}

/** Formats a BPM value with at most one decimal, using the device's decimal separator. */
fun formatBpm(bpm: Double): String = DecimalFormat("0.#").format(bpm)

/**
 * BPM per track URI: detected values and the trainer's corrections, stored as a JSON file.
 * A track has an entry once it was looked up, even if no BPM was found, so it isn't looked up again.
 */
class BpmRepository(context: Context, private val lookup: BpmLookup) {

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val entriesMutex = Mutex()
    private val lookupMutex = Mutex()
    private var loaded = false
    private var lastFailedLookupAt = 0L

    private val _entries = MutableStateFlow<Map<String, BpmEntry>>(emptyMap())
    val entries: StateFlow<Map<String, BpmEntry>> = _entries.asStateFlow()

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
        update { it + newEntries }
        result.complete
    }

    /** Sets the trainer's value; null, or the detected value itself, removes the correction. */
    suspend fun setManualBpm(uri: String, bpm: Double?) = update { entries ->
        val entry = entries[uri] ?: BpmEntry()
        entries + (uri to entry.copy(manualBpm = bpm?.takeIf { it != entry.detectedBpm }))
    }

    /** Multiplies the current value and rounds it, e.g. by 2 or 0.5 to fix double or half tempo. */
    suspend fun multiplyBpm(uri: String, factor: Double) = adjustBpm(uri) { it * factor }

    /** Adds to the current value and rounds it, e.g. +1 or -1 for fine-tuning. */
    suspend fun addBpm(uri: String, delta: Double) = adjustBpm(uri) { it + delta }

    suspend fun setHidden(uri: String, hidden: Boolean) = update { entries ->
        entries + (uri to (entries[uri] ?: BpmEntry()).copy(hidden = hidden))
    }

    private suspend fun adjustBpm(uri: String, adjust: (Double) -> Double) = update { entries ->
        val entry = entries[uri] ?: return@update entries
        val bpm = entry.bpm ?: return@update entries
        val adjusted = adjust(bpm).roundToLong().coerceAtLeast(1).toDouble()
        entries + (uri to entry.copy(manualBpm = adjusted.takeIf { it != entry.detectedBpm }))
    }

    private suspend fun update(transform: (Map<String, BpmEntry>) -> Map<String, BpmEntry>) =
        entriesMutex.withLock {
            loadIfNeeded()
            val updated = transform(_entries.value)
            if (updated != _entries.value) {
                _entries.value = updated
                withContext(Dispatchers.IO) { save(updated) }
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
        val json = JSONObject(file.readFully().decodeToString())
        return json.keys().asSequence().associateWith { uri ->
            val entry = json.getJSONObject(uri)
            BpmEntry(
                detectedBpm = entry.optDoubleOrNull(KEY_DETECTED),
                source = entry.optString(KEY_SOURCE).takeIf { it.isNotEmpty() }?.let { BpmSource.valueOf(it) },
                manualBpm = entry.optDoubleOrNull(KEY_MANUAL),
                hidden = entry.optBoolean(KEY_HIDDEN),
            )
        }
    }

    private fun save(entries: Map<String, BpmEntry>) {
        val json = JSONObject()
        entries.forEach { (uri, entry) ->
            json.put(
                uri,
                JSONObject()
                    .putOpt(KEY_DETECTED, entry.detectedBpm)
                    .putOpt(KEY_SOURCE, entry.source?.name)
                    .putOpt(KEY_MANUAL, entry.manualBpm)
                    .putOpt(KEY_HIDDEN, entry.hidden.takeIf { it })
            )
        }
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key)) getDouble(key) else null

    private companion object {
        const val FILE_NAME = "bpm.json"
        const val KEY_DETECTED = "detected"
        const val KEY_SOURCE = "source"
        const val KEY_MANUAL = "manual"
        const val KEY_HIDDEN = "hidden"
        const val RETRY_DELAY_MS = 60_000L
    }
}
