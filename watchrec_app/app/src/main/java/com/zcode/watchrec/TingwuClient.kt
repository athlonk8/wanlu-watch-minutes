package com.zcode.watchrec

import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class TingwuFailure(message: String, val loginRequired: Boolean = false, val serviceCode: String = "") : IOException(message)

/** Observed website protocol, deliberately separate from the Alibaba developer API. */
object TingwuProtocol {
    const val ORIGIN = "https://tingwu.aliyun.com"
    const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.120 Safari/537.36"

    fun uploadUrl(value: String): URL {
        val uri = try { URI(value) } catch (_: Exception) { throw TingwuFailure("听悟返回的上传地址无效") }
        val host = uri.host.orEmpty().lowercase()
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.port !in listOf(-1, 443) || !host.endsWith(".aliyuncs.com") || host.contains("-internal.")) {
            throw TingwuFailure("听悟返回了不支持的上传地址")
        }
        return uri.toURL()
    }

    fun readJson(input: InputStream): JSONObject {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (output.size() + n > 256 * 1024) throw TingwuFailure("听悟响应过大")
            output.write(buffer, 0, n)
        }
        return try { JSONObject(output.toString("UTF-8")) } catch (_: Exception) { throw TingwuFailure("听悟响应格式已变化，请重新登录后重试") }
    }

    fun data(response: JSONObject): JSONObject {
        val code = response.optString("code")
        if (code in setOf("CMN.NotLogin", "SHA.NotLogin", "TRS.NeedLogin")) throw TingwuFailure("听悟登录已过期，请用支付宝重新扫码", true)
        if (code !in setOf("0", "200")) {
            val message = when {
                code.contains("TransTime", true) -> "听悟可用转写时长不足，请在手机检查额度"
                code.contains("Storage", true) -> "听悟存储空间不足，请在手机检查"
                code.contains("BigModelUserProtocol") -> "请先在手机听悟确认服务协议"
                else -> "听悟暂未接受此操作，请在手机检查账号后重试"
            }
            throw TingwuFailure(message, serviceCode = code.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }.orEmpty())
        }
        return response.optJSONObject("data") ?: JSONObject()
    }

    fun cloudStatus(response: JSONObject, transId: String): Int {
        val records = response.optJSONArray("data") ?: return -1
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (record.optString("transId") == transId) return record.optInt("status", -1).takeIf { it in 0..3 } ?: -1
        }
        return -1
    }
}

class TingwuClient(
    private val cookie: () -> String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val stopped: () -> Boolean = { false },
    private val active: (HttpURLConnection?) -> Unit = {}
) {
    /** Some accounts start processing at file confirmation; don't submit it twice. */
    fun startOrReconcile(transId: String, tag: JSONObject): Int {
        fun status() = TingwuProtocol.cloudStatus(api("getTransStatus", JSONObject().put("transIds", JSONArray().put(transId)).put("preview", 1)), transId)
        val before = status()
        if (before in 0..2) return before
        if (before != 3) throw TingwuFailure("听悟正在确认录音，稍后重试会继续检查同一条记录")
        try {
            api("startTrans", JSONObject().put("userId", "").put("transIds", JSONArray().put(transId)).put("tag", tag))
            return 1 // Explicit success acknowledges the request, even before status catches up.
        } catch (failure: TingwuFailure) {
            if (failure.loginRequired) throw failure
            val after = runCatching { status() }.getOrDefault(-1)
            if (after in 0..2) return after
            throw failure
        }
    }

    fun api(action: String, payload: JSONObject = JSONObject()): JSONObject {
        require(action in setOf("generatePutLink", "syncPutLink", "startTrans", "getTransStatus"))
        if (stopped()) throw TingwuFailure("上传已取消，录音保留在手表")
        val session = cookie()
        if (session.isBlank()) throw TingwuFailure("请先连接听悟账号", true)
        val connection = open(URL("${TingwuProtocol.ORIGIN}/api/trans/request?$action&c=web"))
        active(connection)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Cookie", session)
            connection.setRequestProperty("User-Agent", TingwuProtocol.USER_AGENT)
            connection.setRequestProperty("Referer", "${TingwuProtocol.ORIGIN}/home")
            connection.setRequestProperty("Origin", TingwuProtocol.ORIGIN)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            val bytes = JSONObject(payload.toString()).put("action", action).put("version", "1.0").toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status == 401 || status in 300..399) throw TingwuFailure("请重新扫码连接听悟账号", true)
            if (status != 200) throw TingwuFailure("听悟暂时不可用（$status），录音仍保留")
            val response = connection.inputStream.use(TingwuProtocol::readJson)
            TingwuProtocol.data(response)
            return response
        } finally { connection.disconnect(); active(null) }
    }

    fun put(file: File, signedUrl: String, progress: (Long, Long) -> Unit = { _, _ -> }) {
        if (stopped()) throw TingwuFailure("上传已取消，录音保留在手表")
        val destination = TingwuProtocol.uploadUrl(signedUrl)
        val size = file.length(); val modified = file.lastModified()
        require(size > 0)
        val connection = open(destination)
        active(connection)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 60_000
            connection.requestMethod = "PUT"; connection.doOutput = true
            // Only the signed URL authenticates storage requests; never send account cookies.
            connection.setRequestProperty("Content-Type", "audio/mp4")
            connection.setFixedLengthStreamingMode(size)
            connection.outputStream.use { out ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (sent < size) {
                        if (stopped()) throw TingwuFailure("上传已取消，录音保留在手表")
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                        if (count < 0) throw TingwuFailure("录音文件已变化，已停止上传")
                        out.write(buffer, 0, count); sent += count; progress(sent, size)
                    }
                }
            }
            if (file.length() != size || file.lastModified() != modified) throw TingwuFailure("录音文件已变化，已停止上传")
            if (connection.responseCode == 403) throw TingwuFailure("上传地址已过期，请在手机检查该记录后重新上传")
            if (connection.responseCode !in 200..299) throw TingwuFailure("录音尚未上传完成，请稍后重试")
        } finally { connection.disconnect(); active(null) }
    }
}
