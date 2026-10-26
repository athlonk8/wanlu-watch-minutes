package com.zcode.watchrec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

data class TingwuStatus(val message: String = "", val done: Boolean = false, val progress: Float = 0f, val needsReview: Boolean = false)

object TingwuUploads {
    const val TAG = "tingwu-upload"
    val status = MutableStateFlow<Map<String, TingwuStatus>>(emptyMap())
    fun prefs(ctx: Context) = ctx.getSharedPreferences("tingwu_uploads", Context.MODE_PRIVATE)
    fun automatic(ctx: Context) = prefs(ctx).getBoolean("automatic", false)
    fun states(ctx: Context) = WorkManager.getInstance(ctx).getWorkInfosByTagFlow(TAG)
    fun key(account: String, name: String, size: Long, modified: Long) = UUID.nameUUIDFromBytes("$account/$name/$size/$modified".toByteArray()).toString()
    fun read(ctx: Context, key: String): JSONObject = runCatching { JSONObject(prefs(ctx).getString("job:$key", "{}").orEmpty()) }.getOrDefault(JSONObject())
    @Synchronized fun save(ctx: Context, key: String, job: JSONObject) {
        check(prefs(ctx).edit().putString("job:$key", job.toString()).commit()) { "无法保存上传状态" }
        publish(job)
    }
    @Synchronized private fun publish(job: JSONObject) {
        val name = job.optString("name")
        if (name.isNotBlank() && job.optString("account") == TingwuSession.accountKey.value) status.value = status.value + (name to TingwuStatus(job.optString("message"), job.optString("phase") == "done", job.optDouble("progress", 0.0).toFloat(), job.optBoolean("needsReview") && job.optString("phase") != "starting"))
    }
    fun refresh(ctx: Context) {
        val account = TingwuSession.accountKey.value
        if (account.isBlank()) return
        status.value = emptyMap()
        RecordingStorage.recordings(ctx).forEach { file -> publish(read(ctx, key(account, file.name, file.length(), file.lastModified()))) }
    }
    fun enqueue(ctx: Context, name: String, reviewed: Boolean = false) {
        val file = RecordingStorage.find(ctx, name) ?: error("录音不存在")
        val account = TingwuSession.accountKey.value
        require(account.isNotBlank()) { "请先连接听悟账号" }
        val key = key(account, name, file.length(), file.lastModified())
        if (reviewed && read(ctx, key).optBoolean("needsReview")) prefs(ctx).edit().remove("job:$key").commit()
        val job = read(ctx, key)
        if (job.optString("phase") == "done") { publish(job); return }
        job.put("name", name).put("account", account).put("message", "等待网络，准备上传听悟")
        save(ctx, key, job)
        val request = OneTimeWorkRequestBuilder<TingwuUploadWorker>()
            .setInputData(workDataOf("name" to name, "account" to account, "key" to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag("name:$name").build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("tingwu/$key", ExistingWorkPolicy.KEEP, request)
    }
}

class TingwuUploadWorker(ctx: Context, parameters: WorkerParameters) : Worker(ctx, parameters) {
    companion object { private val gate = Semaphore(1) }
    @Volatile private var connection: HttpURLConnection? = null
    override fun onStopped() { connection?.disconnect() }

    override fun doWork(): Result {
        val name = inputData.getString("name") ?: return Result.failure()
        val key = inputData.getString("key") ?: return Result.failure()
        val account = inputData.getString("account") ?: return Result.failure()
        val ctx = applicationContext
        val file = RecordingStorage.find(ctx, name) ?: return Result.failure()
        if (TingwuUploads.key(account, name, file.length(), file.lastModified()) != key) return Result.failure()
        if (!gate.tryAcquire()) return Result.retry()
        val job = TingwuUploads.read(ctx, key).put("name", name).put("account", account)
        fun save(message: String, phase: String? = null) {
            job.put("message", message)
            if (phase != null) job.put("phase", phase)
            TingwuUploads.save(ctx, key, job)
        }
        try {
            if (!TingwuSession.verify() || TingwuSession.accountKey.value != account) throw TingwuFailure("请连接原听悟账号后重试", true)
            if (job.optString("phase") == "done") return Result.success()
            val manager = ctx.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("tingwu-upload", "听悟直传", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(ctx, "tingwu-upload").setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("腕录 · 直传听悟").setContentText("正在通过手表当前网络上传")
                .setOngoing(true).addAction(0, "取消", WorkManager.getInstance(ctx).createCancelPendingIntent(id)).build()
            setForegroundAsync(ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)).get()
            val sessionCookie = CookieManager.getInstance().getCookie(TingwuSession.ORIGIN).orEmpty()
            val client = TingwuClient({ sessionCookie }, stopped = { isStopped }, active = { connection = it })
            if (job.optString("phase") == "starting") {
                val response = client.api("getTransStatus", JSONObject().put("transIds", JSONArray().put(job.getString("transId"))).put("preview", 1))
                val cloudStatus = TingwuProtocol.cloudStatus(response, job.getString("transId"))
                job.put("lastCloudStatus", cloudStatus).put("needsReview", false)
                if (cloudStatus in 0..2) {
                    job.remove("putLink"); job.remove("getLink")
                    save(if (cloudStatus == 2) "已上传，听悟处理失败，请在手机检查" else "已上传听悟，手机查看结果", "done")
                    return Result.success()
                }
                if (cloudStatus == 3) save("正在继续提交听悟转写", "synced")
                else { save("云端状态尚未确认，点重试继续检查"); return Result.failure() }
            }
            if (job.optString("phase") == "creating") {
                job.put("needsReview", true)
                save("上次请求结果未确认，请先在手机听悟检查该记录")
                return Result.failure()
            }
            if (job.optString("phase").isBlank()) {
                job.put("taskId", UUID.randomUUID().toString()).put("needsReview", false)
                save("正在建立听悟记录", "creating")
                val tag = JSONObject().put("showName", file.nameWithoutExtension).put("fileFormat", "m4a").put("fileType", "local")
                    .put("lang", "cn").put("roleSplitNum", 0).put("translateSwitch", "0").put("transTargetValue", "")
                    .put("originalTag", "{\"isVideo\":false}").put("client", "web")
                val response = client.api("generatePutLink", JSONObject().put("taskId", job.getString("taskId")).put("useSts", 0)
                    .put("fileSize", file.length()).put("dirId", 0).put("fileContentType", "audio/mp4").put("tag", tag))
                val data = response.getJSONObject("data")
                val put = data.optString("putLink"); val get = data.optString("getLink"); val trans = data.optString("transId")
                TingwuProtocol.uploadUrl(put)
                if (get.isBlank() || trans.isBlank()) throw TingwuFailure("听悟上传协议已变化，请保留本地录音")
                job.put("putLink", put).put("getLink", get).put("transId", trans)
                save("正在直接上传录音", "uploading")
            }
            if (job.optString("phase") == "uploading") {
                var last = 0L
                client.put(file, job.getString("putLink")) { sent, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 500 || sent == total) {
                        job.put("progress", sent.toDouble() / total); save("正在上传 ${(sent * 100 / total)}%"); last = now
                    }
                }
                save("听悟正在确认录音", "uploaded")
            }
            if (job.optString("phase") == "uploaded") {
                val metadata = MediaMetadataRetriever()
                val duration = try { metadata.setDataSource(file.absolutePath); metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000) } finally { metadata.release() }
                val body = JSONObject().put("fileLink", job.getString("getLink")).put("fileSize", file.length()).put("transId", job.getString("transId"))
                if (duration != null) body.put("duration", duration)
                client.api("syncPutLink", body)
                save("正在提交听悟转写", "synced")
            }
            if (job.optString("phase") == "synced") {
                save("正在提交听悟转写", "starting")
                val cloudStatus = client.startOrReconcile(job.getString("transId"), JSONObject().put("lang", "cn").put("roleSplitNum", 0).put("translateSwitch", "0"))
                job.put("lastCloudStatus", cloudStatus).remove("serviceCode")
                job.remove("putLink"); job.remove("getLink"); job.put("needsReview", false)
                save(if (cloudStatus == 2) "已上传，听悟处理失败，请在手机检查" else "已上传听悟，手机查看结果", "done")
            }
            return Result.success()
        } catch (error: Exception) {
            if (error is TingwuFailure && error.loginRequired) TingwuSession.connected.value = false
            if (error is TingwuFailure && error.serviceCode.isNotBlank()) {
                job.put("serviceCode", error.serviceCode)
                // An explicit rejection is safe to retry on this same cloud record.
                if (job.optString("phase") == "starting") job.put("phase", "synced").put("needsReview", false)
            }
            if (job.optString("phase") == "creating") job.put("needsReview", true)
            if (job.optString("phase") == "uploading" && error is TingwuFailure && error.message?.contains("过期") == true) job.put("needsReview", true)
            save(if (isStopped) "上传已暂停，录音保留在手表" else if (error is TingwuFailure) error.message.orEmpty() else "网络或服务暂不可用，录音保留在手表，请稍后重试")
            if (job.optString("phase").isBlank() && error !is TingwuFailure && !isStopped && runAttemptCount < 5) return Result.retry()
            return Result.failure()
        } finally { connection?.disconnect(); connection = null; gate.release() }
    }
}
