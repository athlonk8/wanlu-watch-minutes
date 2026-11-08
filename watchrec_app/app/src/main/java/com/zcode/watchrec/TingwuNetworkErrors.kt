package com.zcode.watchrec

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Never surface exception messages: they can contain signed upload URLs. */
internal object TingwuNetworkErrors {
    fun message(error: Throwable): String = when (error) {
        is UnknownHostException -> "域名解析失败，请检查手机共享网络"
        is SocketTimeoutException -> "连接超时，请检查手机共享网络后重试"
        is ConnectException, is NoRouteToHostException -> "无法连接服务器，请检查手机共享网络"
        is SSLException -> "安全连接失败，请检查手表时间与共享网络"
        else -> "网络连接中断，请稍后重试"
    }

    fun failure(error: IOException, requestNotSent: Boolean = false) = TingwuFailure(
        message(error), requestNotSent = requestNotSent, retryable = error !is SSLException
    ).apply { initCause(error) }
}
