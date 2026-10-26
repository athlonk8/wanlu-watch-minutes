package com.zcode.watchrec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The API credential stays on the server; this token only authenticates our private gateway. */
object CloudSettings {
    fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud_v2", Context.MODE_PRIVATE)
    fun endpoint(ctx: Context) = prefs(ctx).getString("endpoint", "").orEmpty()
    fun token(ctx: Context) = prefs(ctx).getString("token", "").orEmpty()
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun delivered(ctx: Context, name: String) = prefs(ctx).getString("sent:$name", null) == endpoint(ctx)
    fun save(ctx: Context, endpoint: String, token: String, enabled: Boolean) {
        prefs(ctx).edit().putString("endpoint", CloudProtocol.endpoint(endpoint))
            .putString("token", token.trim()).putBoolean("enabled", enabled).apply()
    }
}

object CloudProtocol {
    fun endpoint(value: String): String {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/") &&
            (uri.port == -1 || uri.port in 1..65535)) { "请输入 HTTPS 域名，不含路径" }
        return uri.toString().trimEnd('/')
    }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object CloudUploads {
    const val TAG = "cloud-upload"
    fun enqueue(ctx: Context, name: String) {
        require(name.startsWith("REC_") && name.endsWith(".m4a") &&
            !name.contains('/') && !name.contains('\\') && !name.contains(".."))
        val endpoint = CloudProtocol.endpoint(CloudSettings.endpoint(ctx))
        require(CloudSettings.token(ctx).isNotBlank()) { "先设置云端口令" }
        val work = OneTimeWorkRequestBuilder<CloudUploadWorker>()
            .setInputData(workDataOf("name" to name, "endpoint" to endpoint))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag("file:$name").addTag("endpoint:$endpoint").build()
        // Keep queued/running work; allow an explicit retry after a terminal failure.
        WorkManager.getInstance(ctx).enqueueUniqueWork("$endpoint/$name", ExistingWorkPolicy.KEEP, work)
    }
    fun states(ctx: Context) = WorkManager.getInstance(ctx).getWorkInfosByTagFlow(TAG)
    fun check(endpoint: String, token: String): Boolean {
        val conn = URL("${CloudProtocol.endpoint(endpoint)}/api/health").openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            conn.responseCode == 200 && JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                .optString("service") == "wanlu-cloud"
        } finally { conn.disconnect() }
    }
}

class CloudUploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    @Volatile private var connection: HttpURLConnection? = null
    override fun onStopped() { connection?.disconnect() }
    override fun doWork(): Result {
        val name = inputData.getString("name") ?: return Result.failure()
        val endpoint = inputData.getString("endpoint") ?: return Result.failure()
        fun failed(message: String) = Result.failure(workDataOf("message" to message))
        if (endpoint != CloudSettings.endpoint(applicationContext)) return failed("云端地址已变更，请重新上传")
        val file = RecordingStorage.find(applicationContext, name) ?: return failed("录音文件不存在")
        if (file.length() == 0L) return failed("录音文件不存在")
        if (RecorderState.isRecording.value) return Result.retry()
        var conn: HttpURLConnection? = null
        return try {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("upload", "录音上传", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(applicationContext, "upload")
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("腕录 · 上传云端")
                .setContentText("请保持手机热点连接").setOngoing(true)
                .addAction(0, "取消", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
            setForegroundAsync(ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)).get()
            val hash = CloudProtocol.sha256(file)
            conn = URL("$endpoint/api/recordings").openConnection() as HttpURLConnection
            connection = conn
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(file.length())
            conn.setRequestProperty("Authorization", "Bearer ${CloudSettings.token(applicationContext)}")
            conn.setRequestProperty("Content-Type", "audio/mp4")
            conn.setRequestProperty("X-Audio-SHA256", hash)
            conn.setRequestProperty("X-Filename", name)
            conn.outputStream.use { out ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (!isStopped) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (isStopped) return Result.retry()
            when (conn.responseCode) {
                200, 201, 202 -> {
                    val body = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    if (body.optString("id") != hash) failed("云端响应不匹配")
                    else {
                        CloudSettings.prefs(applicationContext).edit().putString("sent:$name", endpoint).commit()
                        Result.success(workDataOf("message" to "已送达云端", "job" to hash))
                    }
                }
                401, 403 -> failed("云端口令不正确")
                408, 429 -> Result.retry()
                in 500..599 -> Result.retry()
                413 -> failed("录音超出云端大小限制")
                else -> failed("上传失败（${conn.responseCode}），请检查云端配置")
            }
        } catch (_: Exception) {
            // File stays on watch; WorkManager persists the request across process death/reboot.
            Result.retry()
        } finally { conn?.disconnect(); connection = null }
    }
}
