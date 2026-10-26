package com.zcode.watchrec

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import kotlin.concurrent.thread

data class BluetoothPeer(val name: String, val address: String)
data class BluetoothTransferState(val file: String = "", val busy: Boolean = false, val sent: Long = 0, val total: Long = 0, val done: Boolean = false, val message: String = "")
object BluetoothTransfers {
    val selected = MutableStateFlow("")
    val state = MutableStateFlow(BluetoothTransferState())
    @SuppressLint("MissingPermission")
    fun phones(context: Context): List<BluetoothPeer> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            .map { BluetoothPeer(it.name ?: "已配对手机", it.address) }
    }
}

class BluetoothTransferService : Service() {
    companion object {
        const val STOP = "com.zcode.watchrec.BLUETOOTH_STOP"
        const val NAME = "recording_name"
        const val ADDRESS = "phone_address"
    }
    @Volatile private var cancelled = false
    @Volatile private var socket: BluetoothSocket? = null
    private var task: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { cancelled = true; socket?.close(); stopSelf(); return START_NOT_STICKY }
        if (task?.isAlive == true) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("bluetooth-transfer", "蓝牙传录音", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 31, Intent(this, BluetoothTransferService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 32, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "bluetooth-transfer").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("腕录 · 蓝牙发送录音").setContentText("请在手机确认接收文件")
            .setContentIntent(open).setOngoing(true).addAction(0, "取消", stop).build()
        ServiceCompat.startForeground(this, 31, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val name = intent?.getStringExtra(NAME).orEmpty()
        val address = intent?.getStringExtra(ADDRESS).orEmpty()
        val file = RecordingStorage.find(this, name)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val device = try { adapter?.bondedDevices?.firstOrNull { it.address == address } } catch (_: SecurityException) { null }
        if (file == null || device == null || RecorderState.isRecording.value) {
            BluetoothTransfers.state.value = BluetoothTransferState(message = "请先结束录音，并选择已配对的手机")
            stopSelf(); return START_NOT_STICKY
        }
        cancelled = false
        BluetoothTransfers.state.value = BluetoothTransferState(file = name, busy = true, total = file.length(), message = "正在连接手机蓝牙…")
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchrec:bluetooth-file").apply { acquire(30 * 60 * 1000L) }
        handler.postDelayed({ cancelled = true; try { socket?.close() } catch (_: Exception) {}; stopSelf() }, 30 * 60 * 1000L)
        task = thread(name = "wanlu-bluetooth-file", isDaemon = true) {
            try {
                val link = device.createRfcommSocketToServiceRecord(UUID.fromString("00001105-0000-1000-8000-00805f9b34fb"))
                socket = link
                val connectionTimeout = Runnable { try { link.close() } catch (_: Exception) {} }
                handler.postDelayed(connectionTimeout, 30_000)
                try { link.connect() } finally { handler.removeCallbacks(connectionTimeout) }
                BluetoothTransfers.state.value = BluetoothTransfers.state.value.copy(message = "请在手机确认接收请求")
                var last = 0L
                BluetoothObex(link.inputStream, link.outputStream).send(file, { cancelled }) { sent, total ->
                    val now = System.currentTimeMillis()
                    if (now - last >= 200 || sent == total) {
                        BluetoothTransfers.state.value = BluetoothTransfers.state.value.copy(sent = sent, total = total, message = "正在发送 ${(sent * 100 / total)}%")
                        last = now
                    }
                }
                BluetoothTransfers.state.value = BluetoothTransfers.state.value.copy(busy = false, done = true, message = "手机已确认接收，请在听悟导入")
            } catch (_: Exception) {
                BluetoothTransfers.state.value = BluetoothTransfers.state.value.copy(busy = false, message = if (cancelled) "传输已取消，原录音仍保留" else "发送失败，请确认手机允许接收蓝牙文件")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                handler.post { stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        cancelled = true; handler.removeCallbacksAndMessages(null)
        try { socket?.close() } catch (_: Exception) {}
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        BluetoothTransfers.state.value = BluetoothTransfers.state.value.copy(busy = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
