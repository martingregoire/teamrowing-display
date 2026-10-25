package de.gregoire.teamrowingdisplay

import android.app.Application
import de.gregoire.teamrowingdisplay.bpm.BpmLookup
import de.gregoire.teamrowingdisplay.bpm.BpmRepository
import de.gregoire.teamrowingdisplay.spotify.SpotifyApi
import de.gregoire.teamrowingdisplay.spotify.SpotifyAuth
import de.gregoire.teamrowingdisplay.sync.BluetoothSyncServer

/** Holds the app-wide instances, shared by all screens. */
class TeamrowingApp : Application() {
    val spotifyAuth by lazy { SpotifyAuth(this) }
    val spotifyApi by lazy { SpotifyApi(spotifyAuth) }
    val bpmRepository by lazy { BpmRepository(this, BpmLookup()) }
    val displaySettingsStore by lazy { DisplaySettingsStore(this) }
    val syncServer by lazy { BluetoothSyncServer(this, bpmRepository) }
}
