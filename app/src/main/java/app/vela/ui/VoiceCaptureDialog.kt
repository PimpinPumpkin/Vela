package app.vela.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.vela.R

/**
 * The listening sheet for on-device voice search (tier-1): a mic that pulses with your voice while
 * Vela's own model records + transcribes on the phone. Back or tapping outside cancels; **Done**
 * stops early and searches whatever was heard (the VAD also auto-stops after a beat of silence).
 *
 * D-pad-first (docs/dpad.md): a raw [Dialog] with a directly-`.focusable()` Done button that
 * auto-focuses (the VelaDialog pattern), so OK activates it with no wasted "enter the dialog" press.
 */
@Composable
fun VoiceCaptureDialog(
    level: Float,
    listening: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // A soft ring that swells with loudness (0..1), so you can see it's hearing you.
                val ring by animateFloatAsState(1f + level.coerceIn(0f, 1f) * 0.9f, label = "voiceLevel")
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(84.dp)
                            .scale(if (listening) ring else 1f)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
                    )
                    Box(
                        Modifier.size(60.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(if (listening) R.string.voice_capture_listening else R.string.voice_capture_starting),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                DoneButton(onDone)
            }
        }
    }
}

/** A filled pill "Done" that auto-focuses in the raw Dialog (the VelaDialog DialogButton pattern). */
@Composable
private fun DoneButton(onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    val dpadFirst = rememberDpadFirstDevice()
    LaunchedEffect(dpadFirst) {
        if (dpadFirst) repeat(30) {
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }
    Text(
        stringResource(R.string.voice_capture_done),
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .focusRequester(fr)
            .dpadHighlight(CircleShape)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .onKeyEvent { ev ->
                if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter) && ev.type == KeyEventType.KeyUp) {
                    onClick(); true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 28.dp, vertical = 10.dp),
    )
}
