package app.vela.web

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** The WebView's own cookie store as an OkHttp jar, for requests made ON BEHALF of a WebView page
 *  (the proxy) and for the per-place requests that ride the aged session (AgedSession): Google
 *  limits NEW anonymous sessions, and the WebView's is the one that has aged into the full review
 *  feed and popular times (2026-09-23).
 *
 *  On a fresh install the store is empty until something writes to it, so the first requests are
 *  a new session exactly like the app's own. What differs is that CookieManager keeps the store on
 *  disk, so the session Google hands back survives restarts and ages, where the app's in-memory jar
 *  starts over at every launch. The consent cookies are seeded here as in the app's jar (a
 *  cookieless EU request is bounced to consent.google.com), and a CONSENT downgrade is refused. */
class WebViewCookieJar : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cm = CookieManager.getInstance()
        val have = cm.getCookie(url.toString())
            ?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()
        if (url.host.endsWith("google.com") && have.none { it.name == "SOCS" }) {
            cm.setCookie("https://www.google.com", "SOCS=CAESHAgBEhIaAB; path=/; domain=.google.com")
            cm.setCookie("https://www.google.com", "CONSENT=YES+; path=/; domain=.google.com")
            return have + listOf(
                Cookie.Builder().domain("google.com").name("SOCS").value("CAESHAgBEhIaAB").path("/").build(),
                Cookie.Builder().domain("google.com").name("CONSENT").value("YES+").path("/").build(),
            )
        }
        return have
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.filterNot { it.name == "CONSENT" && !it.value.startsWith("YES") }
            .forEach { cm.setCookie(url.toString(), it.toString()) }
        cm.flush()
    }
}
