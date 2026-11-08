package com.zcode.watchrec

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User-initiated, credential-free checks on the same default route as login/upload. */
class TingwuNetworkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var lines by remember { mutableStateOf(listOf<String>()) }
                var running by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                fun check() {
                    if (running) return
                    running = true
                    lines = listOf(route(this), "手表时间：" + SimpleDateFormat("MM-dd HH:mm z", Locale.CHINA).format(Date()))
                    scope.launch {
                        try {
                            for ((label, address) in listOf("听悟" to "https://tingwu.aliyun.com/", "阿里登录" to "https://account.aliyun.com/login/login.htm")) {
                                val result = withContext(Dispatchers.IO) { probe(label, address) }
                                lines = lines + result
                            }
                            lines = lines + "仅检查上述入口的 HTTPS 连接；不代表二维码资源或文件上传已成功。"
                        } finally { running = false }
                    }
                }
                LaunchedEffect(Unit) { check() }
                Surface(Modifier.fillMaxSize(), color = Color(0xFF050708)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("共享网络检查", fontSize = 16.sp)
                        lines.forEach { Text(it, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center) }
                        if (running) Text("正在检查…", fontSize = 11.sp)
                        GlossySecondaryButton(onClick = { check() }, enabled = !running) { Text("重新检查", fontSize = 11.sp) }
                        GlossySecondaryButton(onClick = { finish() }) { Text("返回", fontSize = 11.sp) }
                    }
                }
            }
        }
    }

    private fun route(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return "当前无默认网络；蓝牙配对不代表网络已共享"
        val caps = manager.getNetworkCapabilities(network) ?: return "正在切换网络，请稍后重新检查"
        val types = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) types += "蓝牙 / 手机共享"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types += "Wi-Fi / 手机热点"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types += "移动网络"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types += "VPN"
        val proxy = if (manager.getLinkProperties(network)?.httpProxy != null) " · 系统代理" else ""
        val validation = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "已通过系统联网检查" else "系统尚未确认联网；以下以实际连接结果为准"
        return "当前：${types.joinToString(" + ").ifBlank { "系统共享网络" }}$proxy\n$validation"
    }

    private fun probe(label: String, address: String): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(address).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 9_000
            connection.readTimeout = 9_000
            connection.setRequestProperty("User-Agent", TingwuProtocol.USER_AGENT)
            val code = connection.responseCode
            "$label：" + if (code in 200..399) "HTTPS 可达（$code）" else "服务返回 $code"
        } catch (error: Exception) { "$label：${TingwuNetworkErrors.message(error)}" }
        finally { connection?.disconnect() }
    }
}
