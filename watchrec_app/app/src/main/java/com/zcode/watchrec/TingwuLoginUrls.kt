package com.zcode.watchrec

import java.net.URI

/** Only official HTTPS login destinations can be opened by the compatibility UI. */
internal object TingwuLoginUrls {
    private val hosts = setOf("tingwu.aliyun.com", "account.aliyun.com", "login.aliyun.com", "passport.aliyun.com", "login.taobao.com")
    fun official(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null && uri.port in listOf(-1, 443)
    }.getOrDefault(false)

    fun fallback(value: String?): String? = value?.takeIf {
        it.length <= 8192 && official(it) && runCatching {
            val uri = URI(it)
            uri.host == "account.aliyun.com" && uri.path == "/login/login.htm" && uri.rawFragment == null
        }.getOrDefault(false)
    }
}
