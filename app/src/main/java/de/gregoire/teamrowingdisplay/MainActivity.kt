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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.gregoire.teamrowingdisplay.spotify.PlaybackQueue
import de.gregoire.teamrowingdisplay.spotify.Track
import de.gregoire.teamrowingdisplay.ui.theme.TeamrowingDisplayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DisplayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on first creation, so a configuration change doesn't replay the login redirect
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TeamrowingDisplayTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val state = viewModel.state
                    if (state.loggedIn) {
                        val lifecycleOwner = LocalLifecycleOwner.current
                        LaunchedEffect(lifecycleOwner) {
                            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.pollQueue()
                            }
                        }
                        QueueScreen(
                            queue = state.queue,
                            error = state.error,
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
fun QueueScreen(queue: PlaybackQueue?, error: String?, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.now_playing), style = MaterialTheme.typography.labelLarge)
        val current = queue?.currentlyPlaying
        if (current != null) {
            TrackText(current, MaterialTheme.typography.headlineLarge)
        } else {
            Text(stringResource(R.string.nothing_playing), style = MaterialTheme.typography.headlineLarge)
        }

        Text(
            stringResource(R.string.up_next),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 24.dp),
        )
        queue?.upcoming?.take(3)?.forEach { TrackText(it, MaterialTheme.typography.titleLarge) }

        error?.let { ErrorText(it) }
        TextButton(onClick = onLogout, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.logout_button))
        }
    }
}

@Composable
private fun TrackText(track: Track, style: TextStyle) {
    Text("${track.artists} – ${track.title}", style = style)
}

@Composable
private fun ErrorText(message: String) {
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
            error = null,
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
