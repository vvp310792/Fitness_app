package com.fitnessapp.summary.garmin

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fitnessapp.summary.debug.AppLog

/**
 * Persists the one Garmin credential worth persisting: the long-lived OAuth1 token pair
 * (see [GarminOAuth1Token]). Backed by [EncryptedSharedPreferences] (AES-256, key held in
 * the Android Keystore) rather than plain `SharedPreferences` - this is functionally a
 * long-lived password-equivalent for the user's Garmin account, not a UI flag.
 *
 * The Garmin *password* itself never reaches this class or any storage at all - it lives
 * only as a local variable for the duration of [GarminAuthClient.login] and is never
 * written down.
 */
class GarminTokenStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "garmin_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(token: GarminOAuth1Token, email: String) {
        prefs.edit()
            .putString(KEY_OAUTH_TOKEN, token.oauthToken)
            .putString(KEY_OAUTH_TOKEN_SECRET, token.oauthTokenSecret)
            .putString(KEY_EMAIL, email)
            .apply()
        AppLog.i("GarminTokenStore", "OAuth1-токен сохранён")
    }

    fun load(): GarminOAuth1Token? {
        val token = prefs.getString(KEY_OAUTH_TOKEN, null) ?: return null
        val secret = prefs.getString(KEY_OAUTH_TOKEN_SECRET, null) ?: return null
        return GarminOAuth1Token(token, secret)
    }

    /** Just for display ("вы вошли как ...") - never used in any request. */
    fun savedEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun clear() {
        prefs.edit().clear().apply()
        AppLog.i("GarminTokenStore", "OAuth1-токен удалён (выход)")
    }

    val isLoggedIn: Boolean get() = load() != null

    private companion object {
        const val KEY_OAUTH_TOKEN = "oauth_token"
        const val KEY_OAUTH_TOKEN_SECRET = "oauth_token_secret"
        const val KEY_EMAIL = "email"
    }
}
