package com.fitnessapp.summary.garmin

/**
 * The long-lived credential from Garmin's SSO exchange (`oauth/preauthorized`).
 *
 * This is the one thing worth persisting: per garth (the reference implementation this
 * whole flow is grounded in), it stays valid for roughly a year. Everything else -
 * [GarminOAuth2Token], the actual bearer token used on every data call - is short-lived
 * and cheaply re-minted from this pair via one OAuth1-signed request, so there is no
 * "refresh token" flow to implement: a fresh [GarminOAuth2Token] is just another
 * exchange call away, and the user's Garmin password is never needed again after the
 * first login produces this.
 */
data class GarminOAuth1Token(
    val oauthToken: String,
    val oauthTokenSecret: String
)

/**
 * The short-lived bearer token used on every actual data call, minted from
 * [GarminOAuth1Token] via `oauth/exchange/user/2.0`.
 *
 * [expiresAtEpochSeconds] and [refreshTokenExpiresAtEpochSeconds] are both absolute
 * timestamps (computed once at exchange time from the response's `expires_in` /
 * `refresh_token_expires_in`), not durations - storing a duration and comparing it
 * against "how long ago did we mint this" is an easy source of a wall-clock bug when
 * the token briefly outlives its own field name.
 */
data class GarminOAuth2Token(
    val tokenType: String,
    val accessToken: String,
    val expiresAtEpochSeconds: Long
) {
    /** The literal `Authorization` header value, e.g. "Bearer eyJhbG...". */
    val authorizationHeader: String
        get() = "${tokenType.replaceFirstChar { it.uppercaseChar() }} $accessToken"

    /** A minute of slack so a call doesn't start with a token that expires mid-flight. */
    val isExpired: Boolean
        get() = System.currentTimeMillis() / 1000 >= expiresAtEpochSeconds - 60
}

/**
 * The app-level OAuth1 consumer credentials, fetched fresh from garth's public,
 * community-maintained mirror rather than hardcoded - see
 * [GarminAuthClient.consumerCredentials] for why.
 */
data class GarminConsumerCredentials(
    val consumerKey: String,
    val consumerSecret: String
)
