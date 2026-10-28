package de.gregoire.teamrowingdisplay.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class SyncStatus {
    NO_PERMISSION,
    BLUETOOTH_OFF,
    WAITING,
    CONNECTED,
}

/** Bluetooth access needs the "Nearby devices" permission since Android 12. */
fun hasBluetoothPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/** Accepts the remote app's Bluetooth connection and syncs BPM entries with it, one connection at a time. */
class BluetoothSyncServer(private val context: Context, private val store: BpmSyncStore) {

    private val _status = MutableStateFlow(SyncStatus.WAITING)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _remotePlayback = MutableStateFlow<PlaybackUpdate?>(null)

    /** The playback state last sent by the connected remote app, or null if none is connected. */
    val remotePlayback: StateFlow<PlaybackUpdate?> = _remotePlayback.asStateFlow()

    private val _remoteQueue = MutableStateFlow<QueueUpdate?>(null)

    /** The queue last sent by the connected remote app, or null if none is connected or it doesn't send any. */
    val remoteQueue: StateFlow<QueueUpdate?> = _remoteQueue.asStateFlow()

    private val playbackPeer = object : PlaybackSyncPeer {
        override val outgoing: Flow<PlaybackUpdate> = emptyFlow()

        override val outgoingQueues: Flow<QueueUpdate> = emptyFlow()

        override fun received(update: PlaybackUpdate) {
            _remotePlayback.value = update
        }

        override fun receivedQueue(update: QueueUpdate) {
            _remoteQueue.value = update
        }
    }

    /** Serves until cancelled, e.g. while the app is visible; waits while Bluetooth is off or not permitted. */
    suspend fun run() = withContext(Dispatchers.IO) {
        while (true) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            when {
                !hasBluetoothPermission(context) -> _status.value = SyncStatus.NO_PERMISSION
                adapter == null || !adapter.isEnabled -> _status.value = SyncStatus.BLUETOOTH_OFF
                else -> try {
                    serve(adapter)
                } catch (e: IOException) {
                    Log.w(TAG, "Bluetooth sync server failed", e)
                }
            }
            delay(RETRY_DELAY_MS)
        }
    }

    @SuppressLint("MissingPermission") // Checked by hasBluetoothPermission() before
    private suspend fun serve(adapter: BluetoothAdapter) = coroutineScope {
        val serverSocket = adapter.listenUsingRfcommWithServiceRecord(
            SyncProtocol.SERVICE_NAME,
            SyncProtocol.SERVICE_UUID,
        )
        launch { closeOnCancellation(serverSocket) }
        while (true) {
            _status.value = SyncStatus.WAITING
            val socket = serverSocket.accept()
            _status.value = SyncStatus.CONNECTED
            Log.i(TAG, "Remote connected")
            try {
                SyncSession(socket, store, playbackPeer).run()
                Log.i(TAG, "Remote disconnected")
            } catch (e: IOException) {
                Log.i(TAG, "Remote connection lost", e)
            } finally {
                _remotePlayback.value = null
                _remoteQueue.value = null
            }
        }
    }

    private companion object {
        const val TAG = "BluetoothSyncServer"
        const val RETRY_DELAY_MS = 5_000L
    }
}
