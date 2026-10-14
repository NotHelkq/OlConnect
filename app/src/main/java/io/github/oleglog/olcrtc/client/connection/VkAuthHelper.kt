package io.github.oleglog.olcrtc.client.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object VkAuthHelper {
    private const val DEFAULT_APP_ID = "6287487"
    private const val DEFAULT_VERSION = "5.282"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    data class CaptchaChallenge(
        val anonToken: String,
        val link: String,
        val redirectUri: String,
    )

    sealed interface CaptchaResult {
        data class Challenge(val challenge: CaptchaChallenge) : CaptchaResult
        data class Token(val callToken: String) : CaptchaResult
    }

    suspend fun fetchCaptchaChallenge(roomId: String): Result<CaptchaResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Get anonym token
            val postData1 = "client_id=$DEFAULT_APP_ID&token_type=messages&version=1&app_id=$DEFAULT_APP_ID"
            val conn1 = (URL("https://login.vk.ru/?act=get_anonym_token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Origin", "https://vk.ru")
                setRequestProperty("Referer", "https://vk.ru/")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(postData1.toByteArray(Charsets.UTF_8)) }
            }
            val resp1 = conn1.inputStream.bufferedReader().use { it.readText() }
            val json1 = JSONObject(resp1)
            val anonToken = json1.getJSONObject("data").getString("access_token")

            // 2. Extract join link
            var link = roomId.trim()
            if (link.contains("call/join/")) {
                link = link.substringAfter("call/join/")
            }
            link = link.substringBefore("?").substringBefore("#").trim('/')

            // 3. Request anonymous token
            val postData2 = "v=$DEFAULT_VERSION&client_id=$DEFAULT_APP_ID&vk_join_link=" +
                URLEncoder.encode("https://vk.ru/call/join/$link", "UTF-8") +
                "&name=" + URLEncoder.encode("User", "UTF-8")
            val conn2 = (URL("https://api.vk.ru/method/calls.getAnonymousToken").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Origin", "https://vk.ru")
                setRequestProperty("Referer", "https://vk.ru/")
                setRequestProperty("Authorization", "Bearer $anonToken")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(postData2.toByteArray(Charsets.UTF_8)) }
            }
            val resp2 = (if (conn2.responseCode in 200..299) conn2.inputStream else conn2.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json2 = JSONObject(resp2)
            if (json2.has("response")) {
                return@runCatching CaptchaResult.Token(json2.getJSONObject("response").getString("token"))
            }

            val error = json2.getJSONObject("error")
            var redirectUri = error.getString("redirect_uri")
            if (!redirectUri.contains("redirect=")) {
                redirectUri += "&redirect=" + URLEncoder.encode("https://oauth.vk.com/blank.html", "UTF-8")
            }
            CaptchaResult.Challenge(CaptchaChallenge(anonToken = anonToken, link = link, redirectUri = redirectUri))
        }
    }

    suspend fun exchangeSuccessToken(challenge: CaptchaChallenge, successToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val postData = "v=$DEFAULT_VERSION&client_id=$DEFAULT_APP_ID&vk_join_link=" +
                URLEncoder.encode("https://vk.ru/call/join/${challenge.link}", "UTF-8") +
                "&name=" + URLEncoder.encode("User", "UTF-8") +
                "&success_token=" + URLEncoder.encode(successToken, "UTF-8")
            val conn = (URL("https://api.vk.ru/method/calls.getAnonymousToken").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Origin", "https://vk.ru")
                setRequestProperty("Referer", "https://vk.ru/")
                setRequestProperty("Authorization", "Bearer ${challenge.anonToken}")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }
            }
            val resp = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(resp)
            if (json.has("response")) {
                json.getJSONObject("response").getString("token")
            } else {
                val err = json.optJSONObject("error")?.optString("error_msg") ?: resp
                throw IllegalStateException("Captcha validation error: $err")
            }
        }
    }
}
