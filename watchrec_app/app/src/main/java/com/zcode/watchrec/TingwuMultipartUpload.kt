package com.zcode.watchrec

import android.content.Context
import com.alibaba.sdk.android.oss.ClientConfiguration
import com.alibaba.sdk.android.oss.ClientException
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.ServiceException
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider
import com.alibaba.sdk.android.oss.internal.OSSAsyncTask
import com.alibaba.sdk.android.oss.model.ObjectMetadata
import com.alibaba.sdk.android.oss.model.ResumableUploadRequest
import com.alibaba.sdk.android.oss.model.ResumableUploadResult
import org.json.JSONObject
import java.io.File
import java.net.URI

internal class TingwuStorageGrant(value: JSONObject) {
    val endpoint: String
    val bucket = value.optString("bucket")
    val fileKey = value.optString("fileKey")
    val accessKeyId = value.optString("accessKeyId")
    val accessKeySecret = value.optString("accessKeySecret")
    val securityToken = value.optString("securityToken")
    init {
        val raw = value.optString("endpoint").let { if (it.contains("://")) it else "https://$it" }
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null || uri.scheme != "https" || !uri.host.orEmpty().endsWith(".aliyuncs.com") ||
            uri.host.orEmpty().contains("-internal.") || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
            !uri.path.isNullOrEmpty() && uri.path != "/" || uri.rawQuery != null || uri.rawFragment != null ||
            !bucket.matches(Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) || fileKey.isBlank() ||
            accessKeyId.isBlank() || accessKeySecret.isBlank() || securityToken.isBlank()) {
            throw TingwuFailure("听悟分片上传授权无效，录音保留在手表")
        }
        endpoint = "https://${uri.host}"
    }
}

internal object TingwuMultipartUpload {
    const val THRESHOLD = 1024 * 1024L
    fun put(context: Context, file: File, key: String, grant: JSONObject,
            stopped: () -> Boolean, active: (OSSAsyncTask<ResumableUploadResult>?) -> Unit,
            progress: (Long, Long) -> Unit) {
        if (stopped()) throw TingwuFailure("上传已暂停，录音保留在手表")
        val auth = TingwuStorageGrant(grant)
        val client = OSSClient(context, auth.endpoint,
            OSSStsTokenCredentialProvider(auth.accessKeyId, auth.accessKeySecret, auth.securityToken),
            ClientConfiguration().apply {
                connectionTimeout = 30_000; socketTimeout = 60_000
                maxConcurrentRequest = 1; maxErrorRetry = 2
                isHttpDnsEnable = false
                isFollowRedirectsEnable = false
            })
        val records = File(context.filesDir, "tingwu-parts/$key").apply { mkdirs() }
        val size = file.length(); val modified = file.lastModified()
        val request = ResumableUploadRequest(auth.bucket, auth.fileKey, file.absolutePath, records.absolutePath).apply {
            partSize = 256 * 1024L
            setDeleteUploadOnCancelling(false)
            metadata = ObjectMetadata().apply { contentType = "audio/mp4" }
            setProgressCallback { _, sent, total -> progress(sent, total) }
        }
        try {
            val task = client.asyncSequenceUpload(request, null)
            active(task)
            if (stopped()) task.cancel()
            val result = task.result
            if (result.statusCode !in 200..299) throw TingwuFailure("分片上传尚未确认完成，请重试", retryable = true)
            if (file.length() != size || file.lastModified() != modified) throw TingwuFailure("录音文件已变化，已停止上传")
        } catch (error: ServiceException) {
            val expired = error.errorCode in setOf("SecurityTokenExpired", "InvalidAccessKeyId", "InvalidSecurityToken", "AccessDenied")
            throw TingwuFailure(if (expired) "上传授权已过期或不可用，请在手机检查记录后重试" else "分片上传服务暂未接受请求，请稍后重试",
                retryable = error.statusCode == 429 || error.statusCode >= 500).apply { initCause(error) }
        } catch (error: ClientException) {
            if (stopped()) throw TingwuFailure("上传已暂停，录音保留在手表")
            val cause = error.cause
            if (cause is java.io.IOException) throw TingwuNetworkErrors.failure(cause)
            throw TingwuFailure("分片连接中断，稍后将从已完成部分继续", retryable = true).apply { initCause(error) }
        } finally { active(null) }
    }
}
