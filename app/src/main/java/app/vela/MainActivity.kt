package app.vela

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.vela.ui.VelaRoot
import app.vela.ui.theme.VelaTheme
import app.vela.ui.theme.isAppInDarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Read the theme at the call site (a recomposing scope) and pass it in
            // — reading it inside VelaTheme's default arg didn't reliably invalidate
            // VelaTheme, so MaterialTheme never flipped when the user changed it.
            VelaTheme(darkTheme = isAppInDarkTheme()) {
                VelaRoot()
            }
        }
    }
}
