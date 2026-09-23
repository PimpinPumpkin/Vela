package app.vela.web

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** The WebView's own cookie store as an OkHttp jar, for requests made ON BEHALF of a WebView page
 *  (the proxy): they must carry the WebView's session, not the app's. Google limits NEW anonymous
 *  sessions, and the WebView's is the one that has aged into the full review feed (2026-09-23). */
class WebViewCookieJar : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        CookieManager.getInstance().getCookie(url.toString())
            ?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }
}
