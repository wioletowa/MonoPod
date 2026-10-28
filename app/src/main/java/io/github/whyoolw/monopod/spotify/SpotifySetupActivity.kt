package io.github.whyoolw.monopod.spotify

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.github.whyoolw.monopod.Monopod
import io.github.whyoolw.monopod.R
import java.security.MessageDigest

/**
 * Setting up Spotify: what to register at developer.spotify.com for this very APK (redirect,
 * package, the SHA-1 of its signature), and a field for the Client ID that app gets. Touch and
 * the keyboard work here, unlike the iPod.
 */
class SpotifySetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spotify = (application as Monopod).spotify
        val pad = (16 * resources.displayMetrics.density).toInt()
        // The app's window theme has no colors for plain views: a light page of its own
        val ink = 0xFF16161A.toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(s: String, size: Float = 15f) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(ink)
            setPadding(0, pad / 2, 0, pad / 4)
            setTextIsSelectable(true)
        }
        fun copyRow(label: String, value: String) {
            column.addView(text(label, 13f))
            column.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = value
                    textSize = 15f
                    setTextColor(ink)
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    text = getString(R.string.spotify_setup_copy)
                    setOnClickListener {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
                        Toast.makeText(context, R.string.spotify_setup_copied, Toast.LENGTH_SHORT).show()
                    }
                })
            })
        }
        column.addView(text(getString(R.string.spotify_setup_title), 22f))
        column.addView(text(getString(R.string.spotify_setup_intro)))
        copyRow(getString(R.string.spotify_setup_redirect), SpotifyAuth.REDIRECT_URI)
        copyRow(getString(R.string.spotify_setup_package), packageName)
        copyRow(getString(R.string.spotify_setup_sha1), signatureSha1())
        column.addView(text(getString(R.string.spotify_setup_users)))
        column.addView(text(getString(R.string.spotify_setup_paste)))
        val field = EditText(this).apply {
            hint = "0123456789abcdef0123456789abcdef"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setTextColor(ink)
            setHintTextColor(0xFF8A8B90.toInt())
            setText(spotify.auth.clientId)
        }
        column.addView(field)
        column.addView(Button(this).apply {
            text = getString(R.string.spotify_setup_save)
            setOnClickListener {
                val id = field.text.toString().trim()
                if (!Regex("[0-9a-fA-F]{32}").matches(id)) {
                    field.error = getString(R.string.spotify_setup_bad_id)
                    return@setOnClickListener
                }
                spotify.setClientId(id)
                finish()
            }
        })
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF6F6F8.toInt())
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(scroll)
        // Dark status bar icons on the light page
        androidx.core.view.WindowCompat.getInsetsController(window, scroll).isAppearanceLightStatusBars = true
    }

    /** The SHA-1 of the certificate this APK is signed with, as Spotify's dashboard wants it. */
    private fun signatureSha1(): String {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return "?"
        return MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    }
}
