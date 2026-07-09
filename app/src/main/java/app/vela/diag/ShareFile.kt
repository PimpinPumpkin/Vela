package app.vela.diag

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** The one way diagnostic files leave the phone: a FileProvider uri wrapped in a system
 *  share chooser. User-routed by design - Vela never uploads these itself. Shared by
 *  [DiagExporter] and [CrashCatcher] so the grant flags and chooser shape can't drift. */
internal fun shareFileIntent(
    context: Context,
    file: File,
    mime: String,
    subject: String,
    text: String,
    title: String,
): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
