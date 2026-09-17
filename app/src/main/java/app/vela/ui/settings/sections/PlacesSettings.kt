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
 * Places sub-screen (was Place pages; 2026-09-17 settings reshuffle): where the map's places come
 * from, what the map draws for them, and the five place-page content toggles.
 */
@Composable
internal fun PlacesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_places), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        PlacesSourceGroup(topRow)
        Spacer(Modifier.height(8.dp))
        PlacesOnMapGroup()
        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = stringResource(R.string.settings_place_pages)) {
        ToggleRow(
            label = stringResource(R.string.settings_show_reviews),
            checked = app.vela.ui.ShowReviews.on.value,
            onCheckedChange = { app.vela.ui.ShowReviews.set(context, it) },
            hint = stringResource(R.string.settings_show_reviews_hint),
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
