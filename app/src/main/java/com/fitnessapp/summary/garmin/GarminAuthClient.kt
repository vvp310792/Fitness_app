package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Handles Garmin Connect sign-in and keeps the resulting token usable.
 *
 * This is the **unofficial** path (CLAUDE.md, "Источники и цена"): there is no public
 * REST API for pulling your own data on demand - Garmin's own Connect Developer Program
 * is push-only and business-gated - so this instead replicates the private protocol
 * Garmin's own mobile apps use, the same one documented and maintained by the
 * open-source `garth` project (https://github.com/matin/garth). Every endpoint, header,
 * and field name below was read out of garth's current source, not reconstructed from
 * memory - this protocol has changed under everyone using it before, and guessing would
 * just mean a confusing failure for the user with no way to tell login from a Garmin-side
 * change.
 *
 * The whole exchange happens in two phases:
 * 1. SSO login (`sso.garmin.com`) - username/password, possibly followed by an MFA code
 *    - yields a one-time service ticket.
 * 2. Exchange that ticket for a long-lived OAuth1 token pair (`connectapi.garmin.com`),
 *    which is what actually gets persisted (see [GarminTokenStore]). A short-lived OAuth2
 *    bearer token - the thing every data call actually uses - is minted from that OAuth1
 *    pair on demand, so the password is never needed again after this class returns
 *    [GarminLoginResult.Success] once.
 */
sealed class GarminLoginResult {
    data object Success : GarminLoginResult()
    data class MfaRequired(val method: String) : GarminLoginResult()
    data class Failed(val reason: String) : GarminLoginResult()
}

class GarminAuthClient(private val tokenStore: GarminTokenStore) {

    private val client = OkHttpClient.Builder()
        .cookieJar(InMemoryCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // State held only between login() returning MfaRequired and the matching
    // submitMfaCode() call - the SSO session itself lives in the cookie jar above, this
    // is just what the MFA request body needs that the login response told us.
    private var pendingMfaMethod: String? = null
    private var pendingEmail: String? = null

    private var cachedOAuth2: GarminOAuth2Token? = null
    private var cachedConsumer: GarminConsumerCredentials? = null

    val isLoggedIn: Boolean get() = tokenStore.isLoggedIn
    val savedEmail: String? get() = tokenStore.savedEmail()

    // Every public suspend function here wraps its whole body in Dispatchers.IO: the
    // private helpers below (postSso, exchangeTicketAndPersist, exchangeForOAuth2,
    // consumerCredentials) all make *blocking* OkHttp calls (`.execute()`, not OkHttp's
    // async callback API), and these are called from a plain `rememberCoroutineScope()`
    // in the login UI - which defaults to the main dispatcher. Without this, the first
    // tap of "Войти в Garmin" throws NetworkOnMainThreadException instead of logging in.

    suspend fun login(email: String, password: String): GarminLoginResult = withContext(Dispatchers.IO) {
        pendingEmail = email
        try {
            // garth's first move, before any credentials go anywhere: a plain GET to the
            // sign-in page. It's not there for its HTML - it's what seeds the session
            // cookies the login POST right after (and, transitively, the ticket exchange)
            // gets validated against.
            visitSignInPage()
            val json = postSso("/mobile/api/login") {
                put("username", email)
                put("password", password)
                put("rememberMe", false)
                put("captchaToken", "")
            }
            handleSsoResponse(json, email)
        } catch (e: Exception) {
            AppLog.e("GarminAuthClient", "Вход не удался", e)
            GarminLoginResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun submitMfaCode(code: String): GarminLoginResult = withContext(Dispatchers.IO) {
        val method = pendingMfaMethod
            ?: return@withContext GarminLoginResult.Failed("Нет ожидающего входа - начните заново")
        val email = pendingEmail ?: ""
        try {
            val json = postSso("/mobile/api/mfa/verifyCode") {
                put("mfaMethod", method)
                put("mfaVerificationCode", code)
                put("rememberMyBrowser", false)
                put("reconsentList", org.json.JSONArray())
                put("mfaSetup", false)
            }
            handleSsoResponse(json, email)
        } catch (e: Exception) {
            AppLog.e("GarminAuthClient", "Проверка кода не удалась", e)
            GarminLoginResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun logout() {
        tokenStore.clear()
        cachedOAuth2 = null
    }

    /** The current `Authorization: Bearer ...` header value, minting a fresh one if needed. */
    suspend fun ensureAuthorizationHeader(): String? = withContext(Dispatchers.IO) {
        cachedOAuth2?.takeUnless { it.isExpired }?.let { return@withContext it.authorizationHeader }

        val oauth1 = tokenStore.load() ?: return@withContext null
        try {
            val fresh = exchangeForOAuth2(oauth1, isFreshLogin = false)
            cachedOAuth2 = fresh
            fresh.authorizationHeader
        } catch (e: Exception) {
            AppLog.e("GarminAuthClient", "Не удалось обновить OAuth2-токен", e)
            null
        }
    }

    // ---- SSO login/MFA -------------------------------------------------------

    private fun handleSsoResponse(json: JSONObject, email: String): GarminLoginResult {
        val status = json.optJSONObject("responseStatus")
        val type = status?.optString("type") ?: "UNKNOWN"

        return when (type) {
            "SUCCESSFUL" -> {
                val ticket = json.optString("serviceTicketId").ifBlank {
                    return GarminLoginResult.Failed("Ответ SSO без serviceTicketId")
                }
                pendingMfaMethod = null
                visitEmbedPage()
                exchangeTicketAndPersist(ticket, email)
                GarminLoginResult.Success
            }
            "MFA_REQUIRED" -> {
                val method = json.optJSONObject("customerMfaInfo")
                    ?.optString("mfaLastMethodUsed")
                    ?.ifBlank { null } ?: "email"
                pendingMfaMethod = method
                AppLog.i("GarminAuthClient", "Требуется код подтверждения ($method)")
                GarminLoginResult.MfaRequired(method)
            }
            else -> {
                val message = status?.optString("message").orEmpty()
                val detail = if (message.isNotBlank()) "$type: $message" else type
                AppLog.w("GarminAuthClient", "SSO отказал: $detail")
                GarminLoginResult.Failed(detail)
            }
        }
    }

    private fun postSso(path: String, body: JSONObject.() -> Unit): JSONObject {
        val serviceUrl = "https://mobile.integration.$DOMAIN/gcm/android"
        val url = HttpUrl.Builder()
            .scheme("https").host("sso.$DOMAIN").addPathSegments(path.trimStart('/'))
            .addQueryParameter("clientId", CLIENT_ID)
            .addQueryParameter("locale", "en-US")
            .addQueryParameter("service", serviceUrl)
            .build()

        val json = JSONObject().apply(body)
        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .ssoPageHeaders()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("SSO HTTP ${response.code}: $text")
            }
            return JSONObject(text)
        }
    }

    /**
     * garth's very first request of the whole login flow, before any credentials are
     * sent - see [login]. A plain page visit; its own response body is irrelevant, only
     * the cookies it sets matter.
     */
    private fun visitSignInPage() {
        val url = HttpUrl.Builder()
            .scheme("https").host("sso.$DOMAIN").addPathSegments("mobile/sso/en/sign-in")
            .addQueryParameter("clientId", CLIENT_ID)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .ssoPageHeaders(mapOf("Sec-Fetch-Site" to "none"))
            .build()
        client.newCall(request).execute().close()
    }

    /**
     * garth visits this page right after SSO reports success, before exchanging the
     * ticket - and explicitly swallows a failure here (its own `except GarthException:
     * pass`), because it's there for session state, not its response. This step (or the
     * cookies it leaves behind) going missing is exactly what "Обмен тикета вернул HTTP
     * 400" looked like: the account's SSO login succeeded, but the very next call -
     * unsigned and without this page's cookies - had nothing valid to present.
     */
    private fun visitEmbedPage() {
        try {
            val request = Request.Builder()
                .url("https://sso.$DOMAIN/portal/sso/embed")
                .get()
                .ssoPageHeaders(mapOf("Sec-Fetch-Site" to "same-origin"))
                .build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            AppLog.w("GarminAuthClient", "Переход на portal/sso/embed не удался (не критично)", e)
        }
    }

    private fun Request.Builder.ssoPageHeaders(extra: Map<String, String> = emptyMap()): Request.Builder {
        header("User-Agent", SSO_USER_AGENT)
        header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        header("Accept-Language", "en-US,en;q=0.9")
        header("Sec-Fetch-Mode", "navigate")
        header("Sec-Fetch-Dest", "document")
        extra.forEach { (key, value) -> header(key, value) }
        return this
    }

    // ---- OAuth1/OAuth2 exchange ------------------------------------------------

    private fun exchangeTicketAndPersist(ticket: String, email: String) {
        val consumer = consumerCredentials()
        val url = HttpUrl.Builder()
            .scheme("https").host("connectapi.$DOMAIN")
            .addPathSegments("oauth-service/oauth/preauthorized")
            .addQueryParameter("ticket", ticket)
            .addQueryParameter("login-url", "https://mobile.integration.$DOMAIN/gcm/android")
            .addQueryParameter("accepts-mfa-tokens", "true")
            .build()

        // garth signs this call too, even though there's no user OAuth1 token yet -
        // that's what this call produces. Its `GarminOAuth1Session` is still an
        // OAuth1Session regardless, so it still attaches a consumer-only ("two-legged")
        // Authorization header (see GarminOAuth1Signer's tokenKey/tokenSecret defaults).
        // An unsigned request here is exactly what "Обмен тикета вернул HTTP 400" was.
        val authHeader = GarminOAuth1Signer.authorizationHeader(
            method = "GET",
            url = url.toString(),
            consumerKey = consumer.consumerKey,
            consumerSecret = consumer.consumerSecret
        )

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", OAUTH_USER_AGENT)
            .header("Authorization", authHeader)
            .build()

        val oauth1 = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Обмен тикета вернул HTTP ${response.code}: $text")
            }
            // Response is a plain application/x-www-form-urlencoded body, not JSON -
            // "oauth_token=...&oauth_token_secret=...".
            val params = parseFormBody(text)
            val token = params["oauth_token"] ?: throw IllegalStateException("Нет oauth_token в ответе")
            val secret = params["oauth_token_secret"] ?: throw IllegalStateException("Нет oauth_token_secret в ответе")
            GarminOAuth1Token(token, secret)
        }

        tokenStore.save(oauth1, email)
        // Immediately mint a first OAuth2 token too, so ensureAuthorizationHeader() has
        // something cached right after login instead of needing a second round trip.
        cachedOAuth2 = exchangeForOAuth2(oauth1, isFreshLogin = true)
        AppLog.i("GarminAuthClient", "Вход выполнен, OAuth1-токен получен")
    }

    private fun exchangeForOAuth2(oauth1: GarminOAuth1Token, isFreshLogin: Boolean): GarminOAuth2Token {
        val consumer = consumerCredentials()
        val url = "https://connectapi.$DOMAIN/oauth-service/oauth/exchange/user/2.0"
        val bodyParams = if (isFreshLogin) mapOf("audience" to "GARMIN_CONNECT_MOBILE_ANDROID_DI") else emptyMap()

        val authHeader = GarminOAuth1Signer.authorizationHeader(
            method = "POST",
            url = url,
            consumerKey = consumer.consumerKey,
            consumerSecret = consumer.consumerSecret,
            tokenKey = oauth1.oauthToken,
            tokenSecret = oauth1.oauthTokenSecret,
            bodyParams = bodyParams
        )

        val formBody = bodyParams.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        val request = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", OAUTH_USER_AGENT)
            .header("Authorization", authHeader)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Обмен OAuth2 вернул HTTP ${response.code}: $text")
            }
            val json = JSONObject(text)
            val expiresIn = json.optLong("expires_in", 3600L)
            return GarminOAuth2Token(
                tokenType = json.optString("token_type", "Bearer"),
                accessToken = json.getString("access_token"),
                expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + expiresIn
            )
        }
    }

    /**
     * Fetched fresh rather than hardcoded, matching garth exactly: these are Garmin's
     * *app-level* OAuth1 consumer credentials (not the user's own), extracted from the
     * official Garmin Connect mobile app by the reverse-engineering community and
     * published at this well-known URL rather than committed into every project that
     * needs them - so a rotation only needs updating in one shared place, not in every
     * unofficial client (this one included).
     */
    private fun consumerCredentials(): GarminConsumerCredentials {
        cachedConsumer?.let { return it }
        val request = Request.Builder()
            .url("https://thegarth.s3.amazonaws.com/oauth_consumer.json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Не удалось получить OAuth1 consumer credentials: HTTP ${response.code}")
            }
            val json = JSONObject(text)
            val creds = GarminConsumerCredentials(
                consumerKey = json.getString("consumer_key"),
                consumerSecret = json.getString("consumer_secret")
            )
            cachedConsumer = creds
            return creds
        }
    }

    private fun parseFormBody(body: String): Map<String, String> =
        body.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()

    private companion object {
        const val DOMAIN = "garmin.com"
        const val CLIENT_ID = "GCM_ANDROID_DARK"
        const val SSO_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        const val OAUTH_USER_AGENT = "com.garmin.android.apps.connectmobile"
    }
}
