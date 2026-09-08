package com.fitnessapp.summary.scale

import com.fitnessapp.summary.debug.AppLog
import com.fitnessapp.summary.garmin.InMemoryCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class ZeppLoginResult {
    data object Success : ZeppLoginResult()
    data class Failed(val reason: String) : ZeppLoginResult()
}

/**
 * Signs into Zepp Life (ex Mi Fit, `com.xiaomi.hm.health`) with a Xiaomi account, the way
 * the Zepp Life app itself does: a Xiaomi OAuth2 authorization code, exchanged at Zepp's
 * account service for the `app_token` that authenticates every `api-mifit.zepp.com` call.
 *
 * **This is a second unofficial protocol** in the app, next to Garmin's, with the same
 * caveats and one of its own. Every request, header, form field and JSON key here is
 * taken from SmartScaleConnect (https://github.com/AlexxIT/SmartScaleConnect, MIT -
 * `pkg/xiaomi/auth.go` and `pkg/zepp/auth.go`), a working Go implementation of exactly
 * this login, not reconstructed from memory - the same rule the Garmin client follows
 * with garth. The caveat of its own: Xiaomi allows one live session per app, so logging
 * in here **signs the phone's Zepp Life app out**. The token is therefore persisted and
 * reused for as long as Zepp honours it, and this class never re-logs in on its own.
 *
 * The chain, in order:
 * 1. `GET account.xiaomi.com/oauth2/authorize?_json=true&client_id=428135909242707968&pt=1
 *    &redirect_uri=https://api-mifit-cn.huami.com/huami.health.loginview.do&response_type=code`
 *    -> `data.oauthLoginUrl`; then `GET` that URL -> `qs`, `_sign`, `sid`, `callback`.
 *    Every `_json=true` response body from Xiaomi is prefixed with the literal
 *    `&&&START&&&`, which must be stripped before parsing.
 * 2. `POST account.xiaomi.com/pass/serviceLoginAuth2` (form: `_json=true`, `hash` = upper-case
 *    MD5 of the password, `sid`, `callback`, `_sign`, `qs`, `user`; header
 *    `Cookie: deviceId=<random>`) -> `location` (plus `passToken`, `ssecurity`, which this
 *    flow doesn't need). A `captchaUrl` or `notificationUrl` here means Xiaomi wants a
 *    captcha or a 2FA confirmation - neither can be completed from this app, and the user
 *    is told so rather than shown a generic failure.
 * 3. Follow `location` through its redirects (cookies matter) until a `Location` header
 *    carries `code=` - that is the OAuth2 authorization code.
 * 4. `POST account.zepp.com/v2/client/login` (form: `app_name=com.xiaomi.hm.health`,
 *    `app_version=6.14.0`, `code`, `country_code=CN`, `device_id=<uuid>`,
 *    `device_model=phone`, `dn=api-mifit.zepp.com`, `grant_type=request_token`,
 *    `third_name=xiaomi-hm-mifit`) -> `token_info.app_token`, `token_info.user_id`.
 *
 * All blocking OkHttp, so every public function runs under Dispatchers.IO.
 */
class ZeppAuthClient(private val tokenStore: ZeppTokenStore) {

    // Redirects are followed by hand in step 3 - the code lives in a Location header
    // that must be read, not followed.
    private val client = OkHttpClient.Builder()
        .cookieJar(InMemoryCookieJar())
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val isLoggedIn: Boolean get() = tokenStore.isLoggedIn
    val savedAccount: String? get() = tokenStore.savedAccount()
    fun session(): ZeppSession? = tokenStore.load()

    suspend fun login(account: String, password: String): ZeppLoginResult = withContext(Dispatchers.IO) {
        try {
            val code = xiaomiAuthorizationCode(account, password)
                ?: return@withContext ZeppLoginResult.Failed("Xiaomi не вернул код авторизации")
            val session = zeppLogin(code)
            tokenStore.save(session, account)
            AppLog.i("ZeppAuthClient", "Вход в Zepp Life выполнен")
            ZeppLoginResult.Success
        } catch (e: ZeppLoginException) {
            AppLog.w("ZeppAuthClient", "Вход в Zepp Life отклонён: ${e.message}")
            ZeppLoginResult.Failed(e.message ?: "отказ")
        } catch (e: Exception) {
            AppLog.e("ZeppAuthClient", "Вход в Zepp Life не удался", e)
            ZeppLoginResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun logout() = tokenStore.clear()

    // ---- Xiaomi OAuth2 ---------------------------------------------------------------

    private fun xiaomiAuthorizationCode(account: String, password: String): String? {
        // Step 1: the authorize endpoint hands back the real login URL, which hands back
        // the per-attempt signing parameters the credential POST must echo.
        val authorize = getJson("https://account.xiaomi.com/oauth2/authorize?$OAUTH_PARAMS")
        val loginUrl = authorize.optJSONObject("data")?.optString("oauthLoginUrl").orEmpty()
        if (loginUrl.isBlank()) throw ZeppLoginException("Xiaomi не отдал oauthLoginUrl")
        val params = getJson(loginUrl)

        // Step 2: credentials. Xiaomi wants the password as an upper-case MD5 hex, not
        // plain text - one of the details that only a working reference gets right.
        val form = FormBody.Builder()
            .add("_json", "true")
            .add("hash", md5Upper(password))
            .add("sid", params.optString("sid"))
            .add("callback", params.optString("callback"))
            .add("_sign", params.optString("_sign"))
            .add("qs", params.optString("qs"))
            .add("user", account)
            .build()
        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass/serviceLoginAuth2")
            .post(form)
            .header("Cookie", "deviceId=${randomDeviceId()}")
            .build()
        val auth = client.newCall(request).execute().use { parseXiaomiBody(it.body?.string().orEmpty()) }

        val code = auth.optInt("code", 0)
        val location = auth.optString("location")
        when {
            !auth.isNull("notificationUrl") && auth.optString("notificationUrl").isNotBlank() ->
                throw ZeppLoginException("Xiaomi требует подтверждение входа (2FA) — пройдите его в приложении Zepp Life или на account.xiaomi.com и попробуйте снова")
            !auth.isNull("captchaUrl") && auth.optString("captchaUrl").isNotBlank() ->
                throw ZeppLoginException("Xiaomi требует капчу — войдите один раз в Zepp Life на телефоне и попробуйте снова")
            code != 0 || location.isBlank() -> {
                val why = auth.optString("desc").ifBlank { auth.optString("description") }.ifBlank { "код $code" }
                throw ZeppLoginException("Xiaomi отклонил вход: $why")
            }
        }

        // Step 3: chase the redirect chain by hand until the code shows up in a Location.
        var next: String? = location
        var hops = 0
        while (next != null && hops < MAX_REDIRECTS) {
            val response = client.newCall(Request.Builder().url(next).get().build()).execute()
            val redirect = response.use { it.header("Location") }
            if (redirect == null) break
            redirect.toHttpUrlOrNull()?.queryParameter("code")?.let { return it }
            if (redirect.contains("code=")) return redirect.substringAfter("code=").substringBefore('&')
            next = redirect.toHttpUrlOrNull()?.toString() ?: response.request.url.resolve(redirect)?.toString()
            hops++
        }
        return null
    }

    // ---- Zepp token -------------------------------------------------------------------

    private fun zeppLogin(code: String): ZeppSession {
        val form = FormBody.Builder()
            .add("app_name", "com.xiaomi.hm.health")
            .add("app_version", "6.14.0")
            .add("code", code)
            .add("country_code", "CN")
            .add("device_id", UUID.randomUUID().toString())
            .add("device_model", "phone")
            .add("dn", "api-mifit.zepp.com")
            .add("grant_type", "request_token")
            .add("third_name", "xiaomi-hm-mifit")
            .build()
        val request = Request.Builder()
            .url("https://account.zepp.com/v2/client/login")
            .post(form)
            .build()
        val json = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ZeppLoginException("Zepp: HTTP ${response.code}")
            JSONObject(text)
        }
        val tokenInfo = json.optJSONObject("token_info")
            ?: throw ZeppLoginException("Zepp не вернул token_info (${json.optString("result").ifBlank { "без result" }})")
        val appToken = tokenInfo.optString("app_token")
        val userId = tokenInfo.optString("user_id")
        if (appToken.isBlank() || userId.isBlank()) throw ZeppLoginException("Zepp не вернул app_token")
        return ZeppSession(userId = userId, appToken = appToken)
    }

    // ---- helpers ----------------------------------------------------------------------

    private fun getJson(url: String): JSONObject {
        var next: String? = url
        var hops = 0
        // Xiaomi's authorize/login-URL endpoints answer with plain redirects before the
        // JSON body; Go's default client follows them, so this does too.
        while (next != null && hops < MAX_REDIRECTS) {
            val response = client.newCall(Request.Builder().url(next).get().build()).execute()
            response.use {
                val redirect = it.header("Location")
                if (it.isRedirect && redirect != null) {
                    next = it.request.url.resolve(redirect)?.toString()
                    hops++
                } else {
                    return parseXiaomiBody(it.body?.string().orEmpty())
                }
            }
        }
        throw ZeppLoginException("Xiaomi: слишком много переадресаций")
    }

    /** Xiaomi prefixes every `_json=true` body with a sentinel that has to be removed before parsing. */
    private fun parseXiaomiBody(text: String): JSONObject {
        if (!text.startsWith(XIAOMI_PREFIX)) throw ZeppLoginException("Xiaomi: неожиданный формат ответа")
        return JSONObject(text.removePrefix(XIAOMI_PREFIX))
    }

    private fun md5Upper(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    private fun randomDeviceId(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private class ZeppLoginException(message: String) : Exception(message)

    private companion object {
        const val XIAOMI_PREFIX = "&&&START&&&"
        const val MAX_REDIRECTS = 6
        const val OAUTH_PARAMS = "_json=true&" +
            "client_id=428135909242707968&" +
            "pt=1&" +
            "redirect_uri=https://api-mifit-cn.huami.com/huami.health.loginview.do&" +
            "response_type=code"
    }
}
