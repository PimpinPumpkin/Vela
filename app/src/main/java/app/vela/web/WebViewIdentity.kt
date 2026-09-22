package app.vela.web

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.vela.core.config.CalibrationStore
import app.vela.core.data.google.BrowserHeaders

/**
 * One browser identity for every hidden WebView, the same one the OkHttp scrape presents.
 *
 * Measured on a Pixel 4a (2026-09-22) with a header echo: a WebView whose user-agent string is
 * overridden to desktop Chrome still sent `X-Requested-With: app.vela` on EVERY request, plus
 * client hints of its own (`sec-ch-ua: "Android WebView";v="153"`, `sec-ch-ua-mobile: ?1`,
 * `sec-ch-ua-platform: "Android"`), under a Windows Chrome user agent. The package name is the
 * app's name in the clear, and the hints contradict the UA on three counts. The hints are fixed
 * here: the user-agent metadata sets them to the desktop identity (verified on Vanadium 153:
 * Chrome brands, `?0`, Windows, x86 64, a matching full-version list). The package header is
 * NOT fixable by an app: Chromium's removal of it was abandoned after the origin trial, the
 * androidx allow-list API is marked disabled in Chromium's own feature list, and WebView's tests
 * assert the header is the package name on every main-frame and sub-resource request, on
 * Google's WebView as on Vanadium. The allow-list call stays, gated, in case a WebView ever
 * honors it again; the `identity:` log line says whether it did.
 *
 * The UA comes from the calibration bundle (the compiled constant is only its fallback), which
 * is what lets a pushed `userAgent` reach the six WebViews and the OkHttp client together.
 */
object WebViewIdentity {
    fun apply(settings: WebSettings) {
        val cal = CalibrationStore.latest
        settings.userAgentString = cal.userAgent
        val rwSupported = WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
        val rwResult = if (rwSupported) {
            runCatching {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                "allowlist=" + WebSettingsCompat.getRequestedWithHeaderOriginAllowList(settings)
            }.getOrElse { "failed: $it" }
        } else "unsupported"
        // One line per WebView so a diagnostics export says which identity switches took on
        // this phone's WebView build (they differ between Google's WebView and Vanadium).
        android.util.Log.i(
            "VelaWeb",
            "identity: webview=" + (runCatching { android.webkit.WebView.getCurrentWebViewPackage() }.getOrNull()?.let { it.packageName + " " + it.versionName } ?: "?") +
                " requestedWith=" + rwResult +
                " uaMetadata=" + WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA),
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching {
                val major = BrowserHeaders.chromeMajor(cal.userAgent) ?: return
                val brands = BrowserHeaders.brands(cal.secChUa).map { b ->
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand(b.name).setMajorVersion(b.major).setFullVersion("${b.major}.0.0.0").build()
                }
                if (brands.isEmpty()) return
                val meta = UserAgentMetadata.Builder()
                    .setBrandVersionList(brands)
                    .setFullVersion("$major.0.0.0")
                    .setPlatform("Windows")
                    .setPlatformVersion("15.0.0")
                    .setArchitecture("x86")
                    .setBitness(64)
                    .setModel("")
                    .setMobile(false)
                    .setWow64(false)
                    .build()
                WebSettingsCompat.setUserAgentMetadata(settings, meta)
            }
        }
    }
}
