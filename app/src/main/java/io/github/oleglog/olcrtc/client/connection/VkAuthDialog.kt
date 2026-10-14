package io.github.oleglog.olcrtc.client.connection

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object VkAuthDialog {
    enum class Mode {
        LOGIN,
        CAPTCHA,
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        context: Context,
        mode: Mode = Mode.LOGIN,
        initialUrl: String? = null,
        onTokenReceived: (String) -> Unit,
    ) {
        val targetUrl = initialUrl ?: if (mode == Mode.CAPTCHA) {
            "https://id.vk.ru/not_robot_captcha?domain=vk.com&variant=popup&blank=1"
        } else {
            "https://vk.com/login"
        }

        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        }
        container.addView(webView)

        val title = if (mode == Mode.CAPTCHA) "Проверка капчи VK" else "Вход в VK"
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("Отмена", null)
            .create()

        var handled = false

        fun deliverToken(token: String) {
            if (handled || token.isBlank()) return
            handled = true
            (context as? Activity)?.runOnUiThread {
                runCatching { dialog.dismiss() }
                onTokenReceived(token)
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onToken(token: String) {
                deliverToken(token)
            }
        }, "AndroidBridge")

        fun checkUrlForTokens(url: String): Boolean {
            if (handled) return true
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
                val fragment = uri.fragment.orEmpty()
                val fragmentMap = fragment.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else "" to ""
                }
                val token = fragmentMap["access_token"] ?: uri.getQueryParameter("access_token")
                if (!token.isNullOrBlank()) {
                    deliverToken(token)
                    return true
                }
            }
            return false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (checkUrlForTokens(url)) return true
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { checkUrlForTokens(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (handled) return
                url?.let { checkUrlForTokens(it) }

                if (mode == Mode.LOGIN) {
                    val cookies = CookieManager.getInstance().getCookie("https://vk.com") ?: ""
                    val loginCookies = CookieManager.getInstance().getCookie("https://login.vk.ru") ?: ""
                    val allCookies = "$cookies; $loginCookies"

                    if (allCookies.contains("remixsid") || allCookies.contains("remixnsid")) {
                        view?.evaluateJavascript(
                            """
                            (async function() {
                                try {
                                    const r = await fetch("https://login.vk.ru/?act=web_token", {
                                        method: "POST",
                                        headers: { "Content-Type": "application/x-www-form-urlencoded" },
                                        body: "version=1&app_id=6287487",
                                        credentials: "include"
                                    });
                                    const data = await r.json();
                                    if (data && data.data && data.data.access_token) {
                                        AndroidBridge.onToken(data.data.access_token);
                                    }
                                } catch(e) {}
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            webView.stopLoading()
            webView.destroy()
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.loadUrl(targetUrl)
    }
}
