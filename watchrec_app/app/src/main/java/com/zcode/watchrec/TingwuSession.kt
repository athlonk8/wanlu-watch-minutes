package com.zcode.watchrec

import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Reuses only the login established in this app's official Tingwu WebView. */
object TingwuSession {
    const val ORIGIN = TingwuProtocol.ORIGIN
    const val USER_AGENT = TingwuProtocol.USER_AGENT
    val connected = MutableStateFlow(false)
    val accountKey = MutableStateFlow("")

    fun verify(): Boolean {
        val connection = URL("$ORIGIN/api/tingwu/account/info?isLogin&c=web").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "$ORIGIN/home")
            connection.setRequestProperty("Accept", "application/json")
            CookieManager.getInstance().getCookie(ORIGIN)?.let { connection.setRequestProperty("Cookie", it) }
            if (connection.responseCode != 200) throw IOException("听悟登录检查暂不可用")
            val result = connection.inputStream.use(TingwuProtocol::readJson)
            val valid = result.optString("code") in setOf("0", "200") && !result.optJSONObject("data")?.optString("userId").isNullOrBlank()
            if (valid) accountKey.value = MessageDigest.getInstance("SHA-256").digest(result.getJSONObject("data").getString("userId").toByteArray()).joinToString("") { "%02x".format(it) }
            else accountKey.value = ""
            connected.value = valid
            return valid
        } finally { connection.disconnect() }
    }
}
