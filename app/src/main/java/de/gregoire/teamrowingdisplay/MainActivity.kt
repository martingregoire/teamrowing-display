package de.gregoire.teamrowingdisplay

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.gregoire.teamrowingdisplay.ui.theme.TeamrowingDisplayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DisplayViewModel by viewModels()
    private val sessionViewModel: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enterTabletMode()
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
                                viewModel.pollPlayback()
                            }
                        }
                        val bpmEntries by viewModel.bpmEntries.collectAsStateWithLifecycle()
                        DisplayScreen(
                            playback = state.playback,
                            next = state.upcoming.firstOrNull(),
                            bpmEntries = bpmEntries,
                            error = state.error,
                            onPrepareSessions = { showSessions = true },
                            onLogout = viewModel::logout,
                            contentPadding = innerPadding,
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

    /**
     * Keeps the screen on while the app is in the foreground and hides the status and navigation bars.
     * A swipe from the screen edge shows the bars temporarily.
     */
    private fun enterTabletMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

@Composable
fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
fun LoginScreenPreview() {
    TeamrowingDisplayTheme {
        LoginScreen(error = null, onLogin = {})
    }
}
