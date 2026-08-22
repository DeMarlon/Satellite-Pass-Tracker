package com.example.eps_sgtracker

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.eps_sgtracker.notifications.ensureReminderChannel
import com.example.eps_sgtracker.ui.MainAppShell
import com.example.eps_sgtracker.ui.TrackerViewModel
import com.example.eps_sgtracker.ui.theme.EPSSGTrackerTheme

class MainActivity : ComponentActivity() {

    // Instantiate your shared view model here at the activity scope level
    private val trackerViewModel: TrackerViewModel by viewModels()

    /**
     * Coming back after a long time away leaves the forecast's far edge short of the configured
     * horizon, since the in-session tickers only extend it while the app is actually running.
     *
     * The screens filter expired passes against the live clock as they render, so stale rows are not
     * the concern here; this is purely about the window catching up on elapsed time.
     *
     * The elapsed-time gate lives in the ViewModel (see [TrackerViewModel.onAppResumed]) because the
     * threshold it compares against is the ticker interval, which is private to that file. Without a
     * gate this fired on every rotation, permission dialog and app switch, cancelling the retained
     * ViewModel's already-computed passes and replacing the list with a full-screen spinner.
     */
    override fun onResume() {
        super.onResume()
        trackerViewModel.onAppResumed()
    }

    /** Parks the ViewModel's periodic SGP4 / network tickers while nothing is on screen. */
    override fun onPause() {
        super.onPause()
        trackerViewModel.onAppPaused()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Explicitly dark, NOT the default SystemBarStyle.auto(): auto decides bar-icon appearance
        // from the SYSTEM night-mode setting, but this app's Compose theme is unconditionally dark.
        // On a phone in light mode auto therefore set isAppearanceLightStatusBars = true, painting
        // dark icons over the app's near-black background and making the clock, battery and signal
        // indicators effectively invisible.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        // Idempotent - registers the pass-reminder notification channel up front so it exists
        // (and is user-configurable in system settings) before the first reminder ever fires.
        ensureReminderChannel(this)
        setContent {
            // Collected out here rather than inside the shell: the accent has to be in place before
            // the theme is built, since everything below reads it through MaterialTheme.
            val themeColor by trackerViewModel.themeColor.collectAsStateWithLifecycle()
            EPSSGTrackerTheme(primaryOverride = themeColor) {
                MainAppShell(viewModel = trackerViewModel)
            }
        }
    }
}
