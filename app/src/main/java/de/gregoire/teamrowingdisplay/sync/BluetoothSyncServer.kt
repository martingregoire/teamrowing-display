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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            BpmSyncProtocol.SERVICE_NAME,
            BpmSyncProtocol.SERVICE_UUID,
        )
        launch { closeOnCancellation(serverSocket) }
        while (true) {
            _status.value = SyncStatus.WAITING
            val socket = serverSocket.accept()
            _status.value = SyncStatus.CONNECTED
            Log.i(TAG, "Remote connected")
            try {
                BpmSyncSession(socket, store).run()
                Log.i(TAG, "Remote disconnected")
            } catch (e: IOException) {
                Log.i(TAG, "Remote connection lost", e)
            }
        }
    }

    private companion object {
        const val TAG = "BluetoothSyncServer"
        const val RETRY_DELAY_MS = 5_000L
    }
}
