package app.vela.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import app.vela.R

/**
 * Opens http(s) links ONLY in a real installed browser - never in the system WebView.
 *
 * On browser-less devices (common on the keypad/degoogled phones this fork targets) a bare
 * ACTION_VIEW can land in whatever half-browser the ROM ships: the "WebView Browser Tester"
 * shell, an OEM mini-browser, or nothing at all (an ActivityNotFoundException a runCatching
 * used to swallow silently - the tap just did nothing). This helper resolves the REAL
 * browsers up front:
 *  - no real browser installed -> a toast says so and nothing opens;
 *  - the user's default handler is a real browser -> respect it;
 *  - the default handler is a WebView shell (or something non-browser claimed the link)
 *    -> the intent is PINNED to a real browser package, so the shell can never open.
 *
 * Requires the `<queries>` browsable-https entry in the manifest (Android 11+ package
 * visibility - without it queryIntentActivities returns nothing and every link would
 * report "no browser" even with Chrome installed).
 */
object ExternalLinks {
    // WebView shell / tester packages that register BROWSABLE activities on some ROMs but are
    // not browsers.
    private val WEBVIEW_SHELLS = setOf(
        "com.android.webview",
        "com.google.android.webview",
        "org.chromium.webview_shell",
    )

    private fun viewIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Packages of ACTUAL installed browsers. Two ways in, both device-verified:
     *  - declares MAIN + CATEGORY_APP_BROWSER, how every real browser identifies itself
     *    (Chrome, Firefox and friends - including preinstalled system ones);
     *  - OR is a NON-SYSTEM app handling a generic https VIEW - a browser APK the user
     *    installed, whatever categories it declares.
     * What this excludes is the point: the keypad phones' preinstalled fake browser
     * (com.tripleu.dummychromebrowser at /system/app/fake, a WebView shell that renders
     * "Access Denied" for every URL) is a SYSTEM app that declares no APP_BROWSER, so it
     * fails both tests - as do the WebView tester shells. The generic probe URL matters:
     * probing the actual target URL would also match apps that merely deep-link it.
     */
    private fun realBrowsers(context: Context): List<String> {
        val pm = context.packageManager
        val declared = runCatching {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        val sideloaded = runCatching {
            pm.queryIntentActivities(viewIntent("https://example.com/"), PackageManager.MATCH_ALL)
                .filter { it.activityInfo?.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        return (declared + sideloaded)
            .distinct()
            .filter { it != context.packageName && it !in WEBVIEW_SHELLS }
    }

    /** True if at least one real browser is installed. */
    fun browserAvailable(context: Context): Boolean = realBrowsers(context).isNotEmpty()

    /**
     * Open [url] in a real browser. Returns false (after a "install a browser" toast) when
     * none is installed. Non-http(s) schemes are passed through untouched - tel:, market:,
     * package installs and geo: have their own handlers and are not browser links.
     */
    fun open(context: Context, url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        // A SCHEME-LESS value is a web URL: Google's place payload stores bare-domain websites
        // ("moldovarestaurantbrooklyn.com"), and letting those fall into the raw-intent branch
        // reopened the exact hole this helper closes (device-found: the bare domain launched
        // the fake browser the http path correctly refuses).
        val webUrl = if (scheme == null || scheme.isEmpty()) "https://$url" else url
        if (scheme != null && scheme != "http" && scheme != "https") {
            return runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
        val browsers = realBrowsers(context)
        if (browsers.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.no_browser_installed), Toast.LENGTH_LONG).show()
            return false
        }
        val intent = viewIntent(webUrl)
        // Respect the user's default browser; pin the intent when the default handler for the
        // link is NOT a real browser (a WebView shell, or an app that deep-links this URL).
        val default = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        if (default == null || default !in browsers) intent.setPackage(browsers.first())
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
