package app.vela.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.ui.settings.GroupDivider
import app.vela.ui.settings.SettingsGroup
import app.vela.ui.settings.SettingsScaffold
import app.vela.ui.settings.ToggleRow

/**
 * Place pages sub-screen: the five place-content toggles. The whole screen is unreachable in the
 * restricted flavor (the hub hides its row; the toggles are hard-locked in their holders anyway).
 */
@Composable
internal fun PlacePagesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_place_pages), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        ToggleRow(
            label = stringResource(R.string.settings_show_reviews),
            checked = app.vela.ui.ShowReviews.on.value,
            onCheckedChange = { app.vela.ui.ShowReviews.set(context, it) },
            hint = stringResource(R.string.settings_show_reviews_hint),
            // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
            switchModifier = topRow,
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_read_all_reviews),
            checked = app.vela.ui.LiveReviews.on.value,
            onCheckedChange = { app.vela.ui.LiveReviews.set(context, it) },
            hint = stringResource(R.string.settings_read_all_reviews_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_load_photos),
            checked = app.vela.ui.LoadPhotos.on.value,
            onCheckedChange = { app.vela.ui.LoadPhotos.set(context, it) },
            hint = stringResource(R.string.settings_load_photos_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_hide_adult),
            checked = app.vela.ui.HideAdult.on.value,
            onCheckedChange = { app.vela.ui.HideAdult.set(context, it) },
            hint = stringResource(R.string.settings_hide_adult_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_hide_external_links),
            checked = app.vela.ui.HideExternalLinks.on.value,
            onCheckedChange = { app.vela.ui.HideExternalLinks.set(context, it) },
            hint = stringResource(R.string.settings_hide_external_links_hint),
        )
        }
        Spacer(Modifier.height(24.dp))
    }
}
