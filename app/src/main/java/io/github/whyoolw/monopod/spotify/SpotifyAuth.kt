package io.github.whyoolw.monopod.spotify

import io.github.whyoolw.monopod.BuildConfig
import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify sign-in with PKCE: no client secret lives in the app. The browser shows Spotify's
 * login, comes back to [REDIRECT_URI], and the code is swapped for tokens; the refresh token
 * is kept so the user signs in once.
 */
class SpotifyAuth(context: Context) {
    private val prefs = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    val signedIn: Boolean get() = prefs.getString(KEY_REFRESH, null) != null

    /**
     * The Spotify app this install signs in through: set in the app (Set Up Spotify), or built
     * in from local.properties. Each user registers their own at developer.spotify.com.
     */
    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SPOTIFY_CLIENT_ID

    val hasClientId: Boolean get() = clientId.isNotBlank()

    /** A new Client ID belongs to another Spotify app: the old sign-in no longer fits it. */
    fun setClientId(id: String) {
        prefs.edit().clear().putString(KEY_CLIENT_ID, id.trim()).apply()
    }

    /** The URL to open in the browser; remembers the verifier for [finish]. */
    fun loginUrl(): Uri {
        val verifier = randomString(64)
        val state = randomString(16)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .build()
    }

    /** Handles the redirect; blocking, call off the main thread. True when signed in. */
    fun finish(redirect: Uri): Boolean {
        val code = redirect.getQueryParameter("code") ?: return false
        if (redirect.getQueryParameter("state") != prefs.getString(KEY_STATE, null)) return false
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return false
        val json = tokenRequest(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to verifier,
        ) ?: return false
        store(json)
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
        return true
    }

    /** A valid access token, refreshed when it is about to run out; null when signed out. Blocking. */
    @Synchronized
    fun accessToken(): String? {
        val token = prefs.getString(KEY_ACCESS, null)
        if (token != null && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, 0L) - 60_000) return token
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val json = tokenRequest("grant_type" to "refresh_token", "refresh_token" to refresh) ?: return null
        store(json)
        return prefs.getString(KEY_ACCESS, null)
    }

    /** Forgets the sign-in; the Client ID stays. */
    fun signOut() {
        val id = prefs.getString(KEY_CLIENT_ID, null)
        prefs.edit().clear().apply { id?.let { putString(KEY_CLIENT_ID, it) } }.apply()
    }

    private fun store(json: JSONObject) {
        prefs.edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
            // A refresh may or may not hand out a new refresh token
            .apply { json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString(KEY_REFRESH, it) } }
            // What the user actually granted; a refresh may leave it out (then it is unchanged)
            .apply { json.optString("scope").takeIf { it.isNotEmpty() }?.let { putString(KEY_SCOPE, it) } }
            .apply()
    }

    /** Signed in before Monopod asked for some permission it now needs: sign in again. */
    val needsNewLogin: Boolean
        get() = signedIn && (prefs.getString(KEY_SCOPE, "")!!.split(' ').toSet().let { granted -> SCOPES.any { it !in granted } })

    private fun tokenRequest(vararg params: Pair<String, String>): JSONObject? = runCatching {
        val body = (params.toList() + ("client_id" to clientId))
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        try {
            if (c.responseCode != 200) {
                // A revoked refresh token: forget it, the user signs in again
                if (c.responseCode == 400 && params.any { it.second == "refresh_token" }) signOut()
                null
            } else JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return String(CharArray(length) { chars[random.nextInt(chars.length)] })
    }

    companion object {
        const val REDIRECT_URI = "monopod://spotify-callback"
        private val SCOPES = listOf(
            "playlist-read-private", "playlist-read-collaborative", "user-library-read",
            "user-read-recently-played", "user-top-read", "user-follow-read", "app-remote-control", "user-read-playback-state",
            "user-modify-playback-state",
        )
        private const val KEY_VERIFIER = "verifier"
        private const val KEY_STATE = "state"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_EXPIRES = "expires"
        private const val KEY_SCOPE = "scope"
        private const val KEY_CLIENT_ID = "client_id"
    }
}
