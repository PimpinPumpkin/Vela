package app.vela.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Who Android thinks installed Vela, and why updating can cost somebody their car screen.
 *
 * Android Auto refuses to show a sideloaded navigation app (issue #179: the block is Google's, it
 * is keyed on the app rather than on anything Vela does, and nothing in this codebase fixes it).
 * The tools people use to get around it - AAEnabler, King Installer - work by installing the APK
 * with the INSTALL SOURCE set to Play, and some head units accept it on that basis.
 *
 * That setting belongs to the install, not to the file, so the moment Vela updates ITSELF the
 * source becomes Vela and the car drops it until the whole dance is repeated. Obtainium and a
 * plain sideload do the same. So this is not something to fix silently - it is something to say
 * before the update, and then to offer the APK as a file instead, because handing that file to
 * AAEnabler is the step that keeps the car working.
 */
object InstallSource {
    /** Play's package. An install claiming it on a phone that has no Play is the car workaround. */
    private const val PLAY = "com.android.vending"

    /** Who installed us, as Android records it. Null when nothing claims the install. */
    fun installingPackage(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    /**
     * True when this install is the one Android Auto is willing to look at, i.e. it claims to come
     * from Play. Vela is not on Play, so on a phone running this build that claim can only have
     * been put there deliberately - and a self-update would erase it.
     */
    fun setForCar(context: Context): Boolean = installingPackage(context) == PLAY

    /** Hand the downloaded APK to whatever the user wants to do with it - the point is AAEnabler,
     *  but a file manager or a messenger all work, and none of them is ours to assume. */
    fun shareApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
