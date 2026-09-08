package com.fitnessapp.summary.garmin

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * A bare in-memory cookie jar, scoped to one client. Both unofficial login flows in this
 * app need one: Garmin's SSO chain (sign-in page, login POST, MFA verify, embed page,
 * ticket exchange on a *different* host) and Xiaomi's OAuth redirect chase (see
 * scale/ZeppAuthClient.kt) are each tied together only by session cookies, and OkHttp
 * does not persist cookies across calls by default.
 *
 * Matching is by [Cookie.matches], not by exact response host: Garmin sets cookies scoped
 * to the whole `.garmin.com` domain, meant to carry over to `connectapi.garmin.com` too.
 * An earlier, naive version kept cookies keyed by the exact host that set them, which
 * silently dropped every domain-wide cookie the moment a request moved to a different
 * subdomain - part of why the Garmin ticket exchange was failing.
 */
internal class InMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            this.cookies.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
}
