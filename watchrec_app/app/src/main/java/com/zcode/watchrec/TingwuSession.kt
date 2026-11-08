package com.zcode.watchrec

import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
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
    val problem = MutableStateFlow("")

    fun verify(active: (HttpURLConnection?) -> Unit = {}): Boolean = verify(
        CookieManager.getInstance().getCookie(ORIGIN).orEmpty(),
        { it.openConnection() as HttpURLConnection }, active
    )

    internal fun verify(session: String, open: (URL) -> HttpURLConnection, active: (HttpURLConnection?) -> Unit = {}): Boolean {
        var connection: HttpURLConnection? = null
        try {
            connection = open(URL("$ORIGIN/api/tingwu/account/info?isLogin&c=web"))
            active(connection)
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "$ORIGIN/home")
            connection.setRequestProperty("Accept", "application/json")
            if (session.isNotBlank()) connection.setRequestProperty("Cookie", session)
            if (connection.responseCode == 401 || connection.responseCode in 300..399) return expired()
            if (connection.responseCode != 200) throw TingwuFailure("登录检查失败（${connection.responseCode}），请稍后重试", retryable = connection.responseCode == 429 || connection.responseCode in 500..599)
            val result = connection.inputStream.use(TingwuProtocol::readJson)
            if (result.optString("code") in setOf("CMN.NotLogin", "SHA.NotLogin", "TRS.NeedLogin")) return expired()
            TingwuProtocol.data(result)
            val userId = result.optJSONObject("data")?.optString("userId").orEmpty()
            if (userId.isBlank()) throw TingwuFailure("登录检查结果异常，请稍后重试")
            accountKey.value = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }
            connected.value = true
            problem.value = ""
            return true
        } catch (error: IOException) {
            val failure = if (error is TingwuFailure) error else TingwuNetworkErrors.failure(error)
            problem.value = failure.message.orEmpty()
            throw failure
        } finally { connection?.disconnect(); active(null) }
    }

    private fun expired(): Boolean {
        connected.value = false
        accountKey.value = ""
        problem.value = "请用支付宝重新扫码连接听悟"
        return false
    }
}
