package io.github.whyoolw.monopod.yandex

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.github.whyoolw.monopod.Monopod

/**
 * Yandex's own sign-in page. After login Yandex redirects with the token in the address
 * (#access_token=…); we take it and close. Touch and the keyboard work here, unlike the iPod.
 */
class YandexLoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = take(request.url.toString())
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { take(url) }
        }
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(web)
        web.loadUrl("https://oauth.yandex.ru/authorize?response_type=token&client_id=${YandexMusic.CLIENT_ID}")
    }

    /** True when [url] carries the token: it is saved and the page closes. */
    private fun take(url: String): Boolean {
        val fragment = Uri.parse(url).fragment ?: return false
        val token = fragment.split('&').firstOrNull { it.startsWith("access_token=") }?.substringAfter('=') ?: return false
        (application as Monopod).yandex.saveToken(token)
        finish()
        return true
    }
}
