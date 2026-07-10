package app.vela.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.vela.R

/**
 * A plain-language "why we need this, and you can say no" screen shown BEFORE Android's raw
 * system permission dialog. Google's own guidance is to explain a sensitive permission in
 * context first; a bare system prompt with no rationale gets denied more and trains distrust.
 *
 * Reused across the app so every permission ask reads the same way:
 *  - onboarding location (VelaRoot)
 *  - the voice-search microphone at first use (point-of-use, a later branch)
 *
 * [onAllow] should trigger the actual system permission request; [onNotNow] just dismisses.
 * Neither is destructive - declining always leaves a working, if reduced, app.
 */
@Composable
fun PermissionRationale(
    title: String,
    body: String,
    allowText: String,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    notNowText: String = stringResource(R.string.root_not_now),
) {
    VelaDialog(
        onDismissRequest = onNotNow,
        title = title,
        confirmText = allowText,
        onConfirm = onAllow,
        dismissText = notNowText,
        onDismiss = onNotNow,
        text = { Text(body) },
    )
}
