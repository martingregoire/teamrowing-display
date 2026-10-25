package de.gregoire.teamrowingdisplay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.gregoire.teamrowingdisplay.bpm.BpmEntry
import de.gregoire.teamrowingdisplay.bpm.BpmSource
import de.gregoire.teamrowingdisplay.bpm.formatBpm
import de.gregoire.teamrowingdisplay.spotify.PlaybackQueue
import de.gregoire.teamrowingdisplay.spotify.Track
import de.gregoire.teamrowingdisplay.ui.theme.TeamrowingDisplayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DisplayViewModel by viewModels()
    private val sessionViewModel: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on first creation, so a configuration change doesn't replay the login redirect
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TeamrowingDisplayTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val state = viewModel.state
                    var showSessions by rememberSaveable { mutableStateOf(false) }
                    if (state.loggedIn && showSessions) {
                        SessionsScreen(
                            viewModel = sessionViewModel,
                            onClose = { showSessions = false },
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else if (state.loggedIn) {
                        val lifecycleOwner = LocalLifecycleOwner.current
                        LaunchedEffect(lifecycleOwner) {
                            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.pollQueue()
                            }
                        }
                        val bpmEntries by viewModel.bpmEntries.collectAsStateWithLifecycle()
                        QueueScreen(
                            queue = state.queue,
                            bpmEntries = bpmEntries,
                            error = state.error,
                            onPrepareSessions = { showSessions = true },
                            onLogout = viewModel::logout,
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else {
                        LoginScreen(
                            error = state.error,
                            onLogin = ::startLogin,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.toString().startsWith(BuildConfig.SPOTIFY_REDIRECT_URI)) {
            viewModel.handleRedirect(uri)
        }
    }

    private fun startLogin() {
        CustomTabsIntent.Builder().build().launchUrl(this, viewModel.createAuthorizeUri())
    }
}

@Composable
fun LoginScreen(error: String?, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.login_hint), style = MaterialTheme.typography.titleLarge)
        Button(onClick = onLogin) {
            Text(stringResource(R.string.login_button))
        }
        error?.let { ErrorText(it) }
    }
}

/** Temporary screen showing the raw queue data, to verify the Spotify connection. */
@Composable
fun QueueScreen(
    queue: PlaybackQueue?,
    bpmEntries: Map<String, BpmEntry>,
    error: String?,
    onPrepareSessions: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.now_playing), style = MaterialTheme.typography.labelLarge)
        val current = queue?.currentlyPlaying
        if (current != null) {
            TrackText(current, bpmEntries[current.uri], MaterialTheme.typography.headlineLarge)
        } else {
            Text(stringResource(R.string.nothing_playing), style = MaterialTheme.typography.headlineLarge)
        }

        Text(
            stringResource(R.string.up_next),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 24.dp),
        )
        queue?.upcoming?.take(3)?.forEach { TrackText(it, bpmEntries[it.uri], MaterialTheme.typography.titleLarge) }

        error?.let { ErrorText(it) }
        Row(modifier = Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPrepareSessions) {
                Text(stringResource(R.string.prepare_sessions))
            }
            TextButton(onClick = onLogout) {
                Text(stringResource(R.string.logout_button))
            }
        }
    }
}

@Composable
private fun TrackText(track: Track, bpmEntry: BpmEntry?, style: TextStyle) {
    val title = "${track.artists} – ${track.title}"
    if (bpmEntry?.hidden == true) {
        Text(title, style = style)
    } else {
        val bpm = bpmEntry?.bpm?.let(::formatBpm) ?: "–"
        Text("[$bpm] $title", style = style)
    }
}

@Composable
fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
fun QueueScreenPreview() {
    TeamrowingDisplayTheme {
        QueueScreen(
            queue = PlaybackQueue(
                currentlyPlaying = Track("spotify:track:1", "Eye of the Tiger", "Survivor", 245_000),
                upcoming = listOf(
                    Track("spotify:track:2", "Lose Yourself", "Eminem", 326_000),
                    Track("spotify:track:3", "Can't Hold Us", "Macklemore & Ryan Lewis", 258_000),
                ),
            ),
            bpmEntries = mapOf(
                "spotify:track:1" to BpmEntry(109.0, BpmSource.RECCOBEATS, manualBpm = 27.25),
                "spotify:track:2" to BpmEntry(171.0, BpmSource.DEEZER),
            ),
            error = null,
            onPrepareSessions = {},
            onLogout = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
fun LoginScreenPreview() {
    TeamrowingDisplayTheme {
        LoginScreen(error = null, onLogin = {})
    }
}
