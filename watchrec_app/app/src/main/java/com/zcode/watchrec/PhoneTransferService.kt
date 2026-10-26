package com.zcode.watchrec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address

data class PhoneShareState(val file: String = "", val url: String = "", val pin: String = "", val expiresAt: Long = 0, val delivered: Boolean = false, val error: String = "")
object PhoneShare {
    val state = MutableStateFlow(PhoneShareState())
    fun addresses(context: Context): List<String> = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.allNetworks.filter { network ->
            val caps = manager.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .map { it.address }.filter { it is Inet4Address && it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct()
    } catch (_: Exception) { emptyList() }
}

class PhoneTransferService : Service() {
    companion object {
        const val STOP = "com.zcode.watchrec.PHONE_STOP"
        const val NAME = "recording_name"
        private const val LIFETIME = 15 * 60 * 1000L
        private const val NOTIFICATION = 21
    }
    private var server: PhoneTransferServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val active = server ?: return
            if (System.currentTimeMillis() >= active.expiresAt) { stopSelf(); return }
            val ip = PhoneShare.addresses(this@PhoneTransferService).firstOrNull()
            PhoneShare.state.value = PhoneShare.state.value.copy(url = if (ip == null) "" else "http://$ip:${active.port}/#${active.token}")
            handler.postDelayed(this, 2000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("phone-transfer", "传到手机", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 21, Intent(this, PhoneTransferService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 22, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "phone-transfer").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("腕录 · 传到手机").setContentText("仅分享选中的录音 · 15 分钟后关闭")
            .setContentIntent(open).setOngoing(true).addAction(0, "结束传输", stop).build()
        ServiceCompat.startForeground(this, NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        handler.removeCallbacksAndMessages(null)
        server?.close(); server = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        val name = intent?.getStringExtra(NAME).orEmpty()
        val file = RecordingStorage.find(this, name)
        if (file == null || RecorderState.isRecording.value) {
            PhoneShare.state.value = PhoneShareState(error = "请先结束录音，再选择文件传输")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            server = PhoneTransferServer(file, { (PhoneShare.addresses(this) + listOf("127.0.0.1", "localhost")).toSet() },
                { asset -> assets.open("phone-transfer/$asset").use { it.readBytes() } },
                onDelivered = { PhoneShare.state.value = PhoneShare.state.value.copy(delivered = true) }, requestedPort = 8735)
            PhoneShare.state.value = PhoneShareState(file = name, pin = server!!.pin, expiresAt = server!!.expiresAt)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchrec:phone-transfer").apply { acquire(LIFETIME) }
            handler.post(refresh)
        } catch (_: Exception) {
            PhoneShare.state.value = PhoneShareState(error = "无法启动传输，请重试")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.close(); server = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        PhoneShare.state.value = PhoneShareState(error = PhoneShare.state.value.error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
