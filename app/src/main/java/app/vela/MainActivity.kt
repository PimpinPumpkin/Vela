package app.vela

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.vela.core.data.MapLinkParser
import app.vela.ui.AdaptiveDensity
import app.vela.ui.AppLocale
import app.vela.ui.VelaRoot
import app.vela.ui.map.MapViewModel
import app.vela.ui.theme.VelaTheme
import app.vela.ui.theme.isAppInDarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Same instance the Compose tree gets (both resolve to this activity's store),
    // so a deep link handled here shows up in the UI.
    private val vm: MapViewModel by viewModels()

    /** Apply the adaptive small-screen density (no-op on normal screens) AND the in-app language
     * override (no-op when following the system locale) to this Activity's resources. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(AdaptiveDensity.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A language change re-creates this Activity so the whole UI re-reads localized resources.
        AppLocale.onLocaleChanged = { recreate() }
        handleIntent(intent)
        setContent {
            // Read the theme at the call site (a recomposing scope) and pass it in
            // - reading it inside VelaTheme's default arg didn't reliably invalidate
            // VelaTheme, so MaterialTheme never flipped when the user changed it.
            VelaTheme(darkTheme = isAppInDarkTheme()) {
                VelaRoot(vm = vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.vela.ui.AppVisibility.foreground.value = true
    }

    override fun onStop() {
        super.onStop()
        app.vela.ui.AppVisibility.foreground.value = false
    }

    /** Vela registers for `geo:` URIs and Google-Maps web links so it can be the
     * system maps handler; turn whichever we got into a search or a dropped pin. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data?.toString() ?: return
                MapLinkParser.parse(data)?.let { vm.openDeepLink(it) }
            }
            // Share TO Vela: a maps link or geo: URL opens like a deep link, plain text
            // (an address someone texted you) just searches.
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                vm.openSharedText(text)
            }
        }
    }
}
