package com.fitnessapp.summary.garmin

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A minimal OAuth 1.0a HMAC-SHA1 request signer - just enough for the one endpoint that
 * needs it in the whole unofficial Garmin flow: exchanging the OAuth1 token (from SSO
 * login) for an OAuth2 bearer token. Every other Garmin Connect API call afterwards is
 * a plain `Authorization: Bearer <token>` header, no signing involved.
 *
 * There's no OAuth1 library in this project's dependencies, and pulling one in for a
 * single call site isn't worth it - the algorithm is a short, standard, testable
 * function. Implements the subset of RFC 5849 that this one request actually needs:
 * HMAC-SHA1 only, form-urlencoded body params folded into the signature base string
 * alongside the oauth_* params (RFC 5849 §3.4.1.3), no query-string params (this
 * endpoint has none).
 *
 * Verified against garth (https://github.com/matin/garth), the reference Python
 * implementation this whole package's protocol understanding is grounded in - not
 * reconstructed from memory.
 */
object GarminOAuth1Signer {

    /**
     * Builds the `Authorization` header value for a request to [url] with the given
     * form-urlencoded [bodyParams], signed with [consumerKey]/[consumerSecret] (the
     * app-level OAuth1 credentials - see [GarminConsumerCredentials]).
     *
     * [tokenKey]/[tokenSecret] are the user's own OAuth1 token from the SSO exchange -
     * but they're optional, defaulting to none. Garmin's ticket-exchange call
     * (`oauth/preauthorized`, see [GarminAuthClient.exchangeTicketAndPersist]) has no
     * user token yet - that's what it produces - and still needs a *consumer-only*
     * ("two-legged") signature: garth signs it with the same `OAuth1Session` class it
     * uses everywhere else, just constructed without a resource owner key/secret. Per
     * RFC 5849, that means `oauth_token` is omitted entirely (not sent as ""), and the
     * signing key's token-secret half is empty.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun authorizationHeader(
        method: String,
        url: String,
        consumerKey: String,
        consumerSecret: String,
        tokenKey: String = "",
        tokenSecret: String = "",
        bodyParams: Map<String, String> = emptyMap()
    ): String {
        val oauthParams = mutableMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_nonce" to nonce(),
            "oauth_version" to "1.0"
        )
        if (tokenKey.isNotEmpty()) {
            oauthParams["oauth_token"] = tokenKey
        }

        // RFC 5849 3.4.1.3: the base string covers oauth_* params, the request's own
        // form-urlencoded body params, AND any query-string params on [url] together,
        // sorted as one set - not just the oauth_* ones. The ticket-exchange GET is the
        // one call here that actually has query params (ticket/login-url/accepts-mfa-
        // tokens); skipping them - or the body params - produces a signature the server
        // recomputes differently and rejects.
        val queryParams = mutableMapOf<String, String>()
        val parsedUrl = url.toHttpUrl()
        for (i in 0 until parsedUrl.querySize) {
            queryParams[parsedUrl.queryParameterName(i)] = parsedUrl.queryParameterValue(i).orEmpty()
        }

        val allParams = (oauthParams + queryParams + bodyParams).toSortedMap()
        val paramString = allParams.entries.joinToString("&") { (k, v) ->
            "${percentEncode(k)}=${percentEncode(v)}"
        }

        val baseString = listOf(
            method.uppercase(),
            percentEncode(baseUrl(url)),
            percentEncode(paramString)
        ).joinToString("&")

        val signingKey = "${percentEncode(consumerSecret)}&${percentEncode(tokenSecret)}"
        val signature = hmacSha1(signingKey, baseString)

        oauthParams["oauth_signature"] = signature
        val headerParams = oauthParams.entries.joinToString(", ") { (k, v) ->
            "${percentEncode(k)}=\"${percentEncode(v)}\""
        }
        return "OAuth $headerParams"
    }

    /** Strips any query string - the signature base string uses the bare request URL. */
    private fun baseUrl(url: String): String = url.substringBefore('?')

    @OptIn(ExperimentalEncodingApi::class)
    private fun hmacSha1(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encode(raw)
    }

    private fun nonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * RFC 3986 unreserved-character percent-encoding, per RFC 5849 §3.6 - deliberately
     * NOT [java.net.URLEncoder], which follows application/x-www-form-urlencoded rules
     * instead (encodes '~', uses '+' for space) and would produce signatures the server
     * rejects.
     */
    private fun percentEncode(value: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val builder = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (unreserved.indexOf(c) >= 0 && byte >= 0) {
                builder.append(c)
            } else {
                builder.append("%%%02X".format(byte.toInt() and 0xFF))
            }
        }
        return builder.toString()
    }
}
