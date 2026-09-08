package com.fitnessapp.summary.scale

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fitnessapp.summary.debug.AppLog

/**
 * Persists the Zepp Life session: the Zepp `user_id` + `app_token` pair that every data
 * call is authenticated with (see [ZeppAuthClient]). Same storage discipline as
 * [com.fitnessapp.summary.garmin.GarminTokenStore]: [EncryptedSharedPreferences] with a
 * Keystore-held AES-256 key, because this token reads the account's whole weight history.
 *
 * The Xiaomi password itself is never stored - it exists only as a local variable during
 * [ZeppAuthClient.login]. Nor is Xiaomi's own `passToken`: SmartScaleConnect (the reference
 * implementation this follows) never demonstrates re-running the OAuth2 step from it, so
 * rather than guess, an expired Zepp token simply asks the user to log in again.
 */
class ZeppTokenStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "zepp_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(session: ZeppSession, account: String) {
        prefs.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_APP_TOKEN, session.appToken)
            .putString(KEY_ACCOUNT, account)
            .apply()
        AppLog.i("ZeppTokenStore", "Токен Zepp Life сохранён")
    }

    fun load(): ZeppSession? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val appToken = prefs.getString(KEY_APP_TOKEN, null) ?: return null
        return ZeppSession(userId, appToken)
    }

    /** Only for display ("вошли как ...") - never used in any request. */
    fun savedAccount(): String? = prefs.getString(KEY_ACCOUNT, null)

    var uploadToGarmin: Boolean
        get() = prefs.getBoolean(KEY_UPLOAD_TO_GARMIN, true)
        set(value) = prefs.edit().putBoolean(KEY_UPLOAD_TO_GARMIN, value).apply()

    fun clear() {
        prefs.edit().remove(KEY_USER_ID).remove(KEY_APP_TOKEN).remove(KEY_ACCOUNT).apply()
        AppLog.i("ZeppTokenStore", "Токен Zepp Life удалён (выход)")
    }

    val isLoggedIn: Boolean get() = load() != null

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_APP_TOKEN = "app_token"
        const val KEY_ACCOUNT = "account"
        const val KEY_UPLOAD_TO_GARMIN = "upload_to_garmin"
    }
}

/** The authenticated Zepp Life session: the two values every `api-mifit.zepp.com` call needs. */
data class ZeppSession(
    val userId: String,
    val appToken: String
)
