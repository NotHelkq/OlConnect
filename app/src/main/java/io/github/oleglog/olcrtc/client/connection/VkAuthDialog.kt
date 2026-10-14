package io.github.oleglog.olcrtc.client.connection

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import io.github.oleglog.olcrtc.client.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object VkAuthDialog {
    enum class Mode {
        LOGIN,
        CAPTCHA,
    }

    private val Int.dp: Int
        get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        context: Context,
        mode: Mode = Mode.LOGIN,
        initialUrl: String? = null,
        onTokenReceived: (String) -> Unit,
    ) {
        val targetUrl = initialUrl ?: if (mode == Mode.CAPTCHA) {
            "https://id.vk.ru/not_robot_captcha?domain=vk.com&variant=popup&blank=1&redirect=" +
                Uri.encode("https://oauth.vk.com/blank.html")
        } else {
            "https://oauth.vk.com/authorize?client_id=6287487&scope=all&redirect_uri=" +
                Uri.encode("https://oauth.vk.com/blank.html") + "&response_type=token"
        }

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val surfaceColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.BLACK,
        )
        val onSurfaceColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            Color.WHITE,
        )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            minimumWidth = screenWidth
            minimumHeight = screenHeight
            fitsSystemWindows = true
            setBackgroundColor(surfaceColor)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                56.dp,
            )
            setPadding(12.dp, 0, 12.dp, 0)
            setBackgroundColor(surfaceColor)
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 22f
            setTextColor(onSurfaceColor)
            gravity = Gravity.CENTER
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        }

        val titleView = TextView(context).apply {
            text = if (mode == Mode.CAPTCHA) "Проверка капчи VK" else "Вход в VK"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurfaceColor)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp
            }
        }

        val reloadBtn = TextView(context).apply {
            text = "↻"
            textSize = 24f
            setTextColor(onSurfaceColor)
            gravity = Gravity.CENTER
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        }

        val outValue = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            closeBtn.setBackgroundResource(outValue.resourceId)
            reloadBtn.setBackgroundResource(outValue.resourceId)
        }

        header.addView(closeBtn)
        header.addView(titleView)
        header.addView(reloadBtn)
        root.addView(header)

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                3.dp,
            )
            isIndeterminate = false
            max = 100
        }
        root.addView(progressBar)

        val webView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            }
        }
        root.addView(webView)

        val dialog = Dialog(context, R.style.Theme_OlcrtcClient).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        reloadBtn.setOnClickListener { webView.reload() }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        var handled = false
        val mainHandler = Handler(Looper.getMainLooper())

        fun deliverToken(token: String) {
            if (handled || token.isBlank()) return
            handled = true
            mainHandler.post {
                runCatching { dialog.dismiss() }
                onTokenReceived(token.trim())
            }
        }

        fun checkUrlForTokens(url: String): Boolean {
            if (handled || url.isBlank()) return handled

            if (url.contains("success_token=")) {
                val uri = Uri.parse(url)
                val token = uri.getQueryParameter("success_token")
                if (!token.isNullOrBlank()) {
                    deliverToken(token)
                    return true
                }
            }

            if (url.contains("access_token=")) {
                val uri = Uri.parse(url)
                var token = uri.getQueryParameter("access_token")
                if (token.isNullOrBlank()) {
                    val fragment = uri.fragment.orEmpty()
                    token = fragment.split("&")
                        .map { it.split("=", limit = 2) }
                        .firstOrNull { it.size == 2 && it[0] == "access_token" }
                        ?.get(1)
                }
                if (!token.isNullOrBlank()) {
                    deliverToken(token)
                    return true
                }
            }
            return false
        }

        fun checkCookiesForToken() {
            if (handled || mode != Mode.LOGIN) return
            val c1 = cookieManager.getCookie("https://vk.com") ?: ""
            val c2 = cookieManager.getCookie("https://vk.ru") ?: ""
            val c3 = cookieManager.getCookie("https://id.vk.ru") ?: ""
            val c4 = cookieManager.getCookie("https://login.vk.ru") ?: ""
            val combined = "$c1; $c2; $c3; $c4"

            if (combined.contains("remixsid") || combined.contains("remixnsid")) {
                Thread {
                    try {
                        val conn = URL("https://login.vk.ru/?act=web_token").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                        conn.setRequestProperty("Origin", "https://id.vk.ru")
                        conn.setRequestProperty("Referer", "https://id.vk.ru/")
                        conn.setRequestProperty("Cookie", combined)
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        val postData = "version=1&app_id=6287487".toByteArray(Charsets.UTF_8)
                        conn.outputStream.use { it.write(postData) }
                        val resp = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val json = JSONObject(resp)
                        val token = json.optJSONObject("data")?.optString("access_token")
                        if (!token.isNullOrBlank()) {
                            deliverToken(token)
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }.start()
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onToken(token: String) {
                deliverToken(token)
            }
        }, "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (checkUrlForTokens(url)) return true
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    if (checkUrlForTokens(it)) return
                }
                checkCookiesForToken()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let {
                    if (checkUrlForTokens(it)) return
                }
                checkCookiesForToken()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let { checkUrlForTokens(it) }
            }
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (webView.canGoBack()) {
                    webView.goBack()
                    true
                } else {
                    dialog.dismiss()
                    true
                }
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }

        dialog.show()
        dialog.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        webView.loadUrl(targetUrl)
    }
}
