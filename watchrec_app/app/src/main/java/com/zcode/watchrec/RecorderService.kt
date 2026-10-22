package com.zcode.watchrec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecorderState {
    val isRecording = MutableStateFlow(false)
    val startedAt = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
    val filesSummary = MutableStateFlow("")
    val lastFile = MutableStateFlow<String?>(null)

    fun refreshFiles(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val files = dir.listFiles { f -> f.isFile && f.extension == "m4a" }
            ?.sortedByDescending { it.name } ?: emptyList()
        val total = files.sumOf { it.length() }
        filesSummary.value = when {
            files.isEmpty() -> "暂无录音"
            else -> "已存 ${files.size} 段 · ${mb(total)}"
        }
        lastFile.value = files.firstOrNull()?.let { "${it.name}  ${mb(it.length())}" }
    }

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)
}

class RecorderService : Service() {

    companion object {
        const val CHANNEL_ID = "rec"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.zcode.watchrec.STOP"
        private const val MAX_MS = 4 * 60 * 60 * 1000 // 4 小时安全上限
    }

    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var outFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW).apply {
                description = "录音进行中的常驻通知"
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishRecording()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 先占住前台，再初始化录音，避免超时
        goForeground()
        if (!RecorderState.isRecording.value) startRecording()
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val name = "REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        val file = File(dir, name)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchrec:mic").apply {
            setReferenceCounted(false)
            acquire(MAX_MS.toLong())
        }

        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setAudioChannels(1)
            r.setMaxDuration(MAX_MS)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    mainHandler.post {
                        finishRecording()
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            wakeLock?.release(); wakeLock = null
            RecorderState.error.value = "无法启动录音: ${e.message}"
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        recorder = r
        outFile = file
        RecorderState.error.value = null
        RecorderState.startedAt.value = SystemClock.elapsedRealtime()
        RecorderState.isRecording.value = true
        buzz(60)
        goForeground(running = true)
    }

    private fun finishRecording() {
        recorder?.let { r ->
            try {
                r.stop()
            } catch (_: Exception) {
                // 极短时间内停止会抛异常，此时删除空文件
                outFile?.delete()
            }
            r.release()
        }
        recorder = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (RecorderState.isRecording.value) {
            RecorderState.isRecording.value = false
            buzz(90)
            RecorderState.refreshFiles(applicationContext)
        }
        outFile = null
    }

    override fun onDestroy() {
        finishRecording()
        super.onDestroy()
    }

    private fun goForeground(running: Boolean = false) {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (running) "录音中 · 息屏不会停止" else "准备录音…"
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("腕录")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buzz(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms, 200))
        } catch (_: Exception) {
        }
    }
}
