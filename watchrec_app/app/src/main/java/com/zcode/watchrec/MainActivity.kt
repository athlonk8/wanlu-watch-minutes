package com.zcode.watchrec

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF050708)
private val Card = Color(0xFF171D22)
private val Mint = Color(0xFF9CE8C6)
private val Muted = Color(0xFF9BA9AF)
private val Red = Color(0xFFFF776D)

class MainActivity : ComponentActivity() {
    private val chargingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateDebugDisplay() }
    }
    private fun updateDebugDisplay() {
        val plugged = getSystemService(android.os.BatteryManager::class.java).isCharging
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && plugged) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Use the ordinary Tingwu website session; disable legacy developer-backend jobs.
        CloudSettings.prefs(this).edit().putBoolean("enabled", false).apply()
        WorkManager.getInstance(this).cancelAllWorkByTag(CloudUploads.TAG)
        setContent { App() }
    }
    override fun onResume() {
        super.onResume()
        // Debug dock testing only; unplugging immediately restores normal screen timeout.
        registerReceiver(chargingReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateDebugDisplay()
        kotlin.concurrent.thread(name = "tingwu-session", isDaemon = true) {
            runCatching { if (TingwuSession.verify()) TingwuUploads.refresh(applicationContext) }
        }
    }
    override fun onPause() {
        unregisterReceiver(chargingReceiver)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
}

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink, surface = Card),
        typography = Typography(bodyLarge = TextStyle(fontSize = 14.sp))) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            val pager = rememberPagerState { 3 }
            val scope = rememberCoroutineScope()
            Box {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> RecordPage()
                        1 -> Box(Modifier.fillMaxSize().padding(bottom = 26.dp)) {
                            FilesPage(onShare = { scope.launch { pager.animateScrollToPage(2) } })
                        }
                        else -> Box(Modifier.fillMaxSize().padding(bottom = 26.dp)) {
                            PhoneTransferPage(onPick = { scope.launch { pager.animateScrollToPage(1) } })
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { i -> Box(Modifier.size(if (i == pager.currentPage) 5.dp else 4.dp).background(if (i == pager.currentPage) Mint else Muted.copy(alpha = .3f), CircleShape)) }
                }
            }
        }
    }
}

@Composable
fun RecordPage() {
    val ctx = LocalContext.current
    val recording by RecorderState.isRecording.collectAsState()
    val started by RecorderState.startedAt.collectAsState()
    val error by RecorderState.error.collectAsState()
    val summary by RecorderState.filesSummary.collectAsState()
    val activeQuality by RecorderState.recordingQuality.collectAsState()
    var quality by remember { mutableStateOf(RecordingSettings.quality(ctx)) }
    var choosingQuality by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startRecording(ctx)
        else RecorderState.error.value = "需要麦克风权限才能录音"
    }
    LaunchedEffect(recording) {
        if (recording) choosingQuality = false
        RecorderState.refreshFiles(ctx)
        while (recording) { tick = SystemClock.elapsedRealtime(); delay(250) }
    }
    val seconds = if (recording) ((tick - started) / 1000).coerceAtLeast(0) else 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 220.dp
        val controlWidth = (maxWidth * .72f).coerceAtMost(168.dp)
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            drawCircle(Brush.radialGradient(listOf(Mint.copy(alpha = .07f), Color.Transparent)), radius = size.minDimension * .45f)
            drawCircle(Muted.copy(alpha = .12f), style = Stroke(2.dp.toPx()))
            if (recording) drawArc(Red, -90f, (seconds % 60) * 6f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(Modifier.fillMaxSize().padding(top = if (compact) 16.dp else 19.dp, bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Text(if (recording) "●  正在录音" else "腕录", color = if (recording) Red else Mint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60),
                    color = Color.White, fontSize = if (compact) 30.sp else 36.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text(error?.take(32) ?: if (recording) "息屏也会继续录音" else summary.ifEmpty { "随时记录重要的话" },
                    color = if (error == null) Muted else Red, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 25.dp))
            }
            GlossyButton(onClick = {
                if (recording) ctx.startService(Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording(ctx)
                else permission.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
            }, modifier = Modifier.width(controlWidth).height(if (compact) 68.dp else 78.dp)
                    .semantics { contentDescription = if (recording) "结束并保存录音" else "开始录音" },
                tone = if (recording) ButtonTone.Coral else ButtonTone.Mint, contentPadding = PaddingValues(horizontal = 12.dp)) {
                if (recording) Box(Modifier.size(21.dp).background(Color(0xFF602B33), RoundedCornerShape(5.dp)))
                else Canvas(Modifier.size(28.dp)) {
                    drawRoundRect(Ink, topLeft = androidx.compose.ui.geometry.Offset(size.width * .36f, 0f), size = androidx.compose.ui.geometry.Size(size.width * .28f, size.height * .65f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
                    drawArc(Ink, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(size.width * .18f, size.height * .2f), size = androidx.compose.ui.geometry.Size(size.width * .64f, size.height * .65f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    drawLine(Ink, androidx.compose.ui.geometry.Offset(size.width / 2, size.height * .85f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height), 2.dp.toPx())
                }
                Spacer(Modifier.width(9.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (recording) "结束录音" else "开始录音", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(if (recording) "保存到手表" else "记录这一刻", fontSize = 9.sp)
                }
            }
            val shownQuality = if (recording) activeQuality ?: quality else quality
            GlossySecondaryButton(onClick = { choosingQuality = true }, enabled = !recording,
                modifier = Modifier.width(140.dp).height(32.dp).semantics {
                    contentDescription = if (recording) "本段录音音质 ${shownQuality.kbps} kbps，录音期间不可调整" else "录音音质 ${shownQuality.kbps} kbps，点按设置"
                }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text(if (recording) "音质 ${shownQuality.kbps} kbps · 已锁定" else "音质 ${shownQuality.kbps} kbps · 调整", fontSize = 10.sp)
            }
        }
        if (choosingQuality) {
            BackHandler { choosingQuality = false }
            RecordingQualityPicker(quality = quality, onBack = { choosingQuality = false }, onSelect = { picked ->
                if (!RecorderState.isRecording.value) {
                    if (RecordingSettings.select(ctx, picked)) {
                        quality = picked
                        choosingQuality = false
                    } else {
                        choosingQuality = false
                        RecorderState.error.value = "音质未保存，请重试"
                    }
                }
            })
        }
    }
}

@Composable
private fun RecordingQualityPicker(quality: RecordingQuality, onBack: () -> Unit, onSelect: (RecordingQuality) -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
        .padding(start = 32.dp, end = 32.dp, top = 17.dp, bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(28.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Text("‹  音质设置", color = Mint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        RecordingQuality.entries.forEachIndexed { index, option ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            GlossyButton(onClick = { onSelect(option) }, modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
                contentDescription = "${option.title} ${option.kbps} kbps${if (option == quality) "，当前选择" else ""}"
            }, tone = if (option == quality) ButtonTone.Mint else ButtonTone.Slate,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${option.title}  ${option.kbps} kbps", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("约 ${option.megabytesPerHour} MB/小时${if (option == RecordingQuality.HIGH) " · 默认" else ""}",
                        fontSize = 9.sp, color = LocalContentColor.current.copy(alpha = .8f))
                }
                if (option == quality) Text("✓", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun startRecording(ctx: Context) {
    try { ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java)) }
    catch (_: Exception) { RecorderState.error.value = "无法启动，请检查麦克风权限" }
}

@Composable
fun FilesPage(onShare: () -> Unit = {}) {
    val ctx = LocalContext.current
    val files by RecorderState.files.collectAsState()
    val recording by RecorderState.isRecording.collectAsState()
    val share by PhoneShare.state.collectAsState()
    val bluetooth by BluetoothTransfers.state.collectAsState()
    val uploads by TingwuUploads.states(ctx).collectAsState(initial = emptyList())
    val tingwu by TingwuUploads.status.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { RecorderState.refreshFiles(ctx) }
    LaunchedEffect(deleting) { if (deleting != null) { delay(3000); deleting = null } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = if (expanded == null) 36.dp else 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (expanded == null) {
            item { Text("录音", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            item { Text(if (recording) "录音中，结束后可管理" else "${files.size} 段 · 纪要请在手机查看", color = Muted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        } else if (recording) {
            item { Text("录音中，结束后可管理", color = Red, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        }
        if (files.isEmpty()) item { Text("还没有录音\n右滑开始第一段记录", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) }
        items(files, key = { it.name }) { file ->
            val uploading = uploads.any { !it.state.isFinished && "name:${file.name}" in it.tags }
            val busy = share.file == file.name || (bluetooth.busy && bluetooth.file == file.name) || uploading
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF27343C), Card)))
                .clickable(enabled = !recording) { expanded = if (expanded == file.name) null else file.name; deleting = null }.padding(12.dp)) {
                Text(SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(file.modified)), fontSize = 12.sp, color = Color.White)
                Text("%.1f MB · %s".format(file.size / 1048576.0, if (uploading) "正在上传听悟" else if (tingwu[file.name]?.done == true) "已上传听悟" else if (busy) "备用传输已开启" else "保存在手表"),
                    color = if (busy) Mint else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                if (expanded == file.name) {
                    GlossyButton(onClick = {
                        try {
                            if (share.file.isNotEmpty()) ctx.stopService(Intent(ctx, PhoneTransferService::class.java))
                            BluetoothTransfers.selected.value = file.name
                            onShare()
                        } catch (_: Exception) { message = "无法开启传输，请重试" }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !recording && (!bluetooth.busy || bluetooth.file == file.name)) { Text(if (busy || tingwu[file.name]?.done == true) "查看上传状态" else "上传听悟", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    GlossyButton(onClick = {
                        if (deleting == file.name) { RecorderState.deleteFile(ctx, file.name); deleting = null; expanded = null }
                        else deleting = file.name
                    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !recording && !busy, tone = ButtonTone.Coral) { Text(if (deleting == file.name) "再点一次确认删除" else "删除本地录音", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (message.isNotEmpty()) item { Text(message, color = Mint, fontSize = 11.sp) }
        item { Text("左滑查看手机传输", color = Muted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
}

@Composable
fun PhoneTransferPage(onPick: () -> Unit = {}) {
    val ctx = LocalContext.current
    val share by PhoneShare.state.collectAsState()
    val selected by BluetoothTransfers.selected.collectAsState()
    var backup by remember(selected) { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(share.expiresAt) { while (share.expiresAt > 0) { clock = System.currentTimeMillis(); delay(1000) } }
    val bitmap = remember(share.url) {
        if (share.url.isBlank()) null else runCatching {
            val matrix = MultiFormatWriter().encode(share.url, BarcodeFormat.QR_CODE, 256, 256, mapOf(EncodeHintType.MARGIN to 2))
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(256 * 256) { i -> if (matrix[i % 256, i / 256]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                setPixels(pixels, 0, 256, 0, 0, 256, 256)
            }.asImageBitmap()
        }.getOrNull()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (backup || share.file.isNotEmpty()) "备用传输" else "听悟直传", color = Color.White, fontSize = 16.sp)
        if (share.file.isEmpty()) {
            if (backup) {
                GlossySecondaryButton(onClick = { backup = false }) { Text("返回听悟直传", fontSize = 11.sp) }
                BluetoothTransferContent(selected, onPick)
            } else {
                TingwuTransferContent(selected, onPick)
                GlossySecondaryButton(onClick = { backup = true }, modifier = Modifier.padding(top = 16.dp)) { Text("蓝牙 / Wi-Fi 备用传输", fontSize = 10.sp) }
            }
            if (share.error.isNotEmpty()) Text(share.error, color = Red, fontSize = 10.sp)
        } else {
            Text(if (share.delivered) "已传输 · 请在手机确认下载" else "手机扫码接收", color = Mint, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp, bottom = 7.dp), textAlign = TextAlign.Center)
            if (bitmap != null) Image(bitmap, "手机扫描此二维码接收选中的录音", Modifier.size(128.dp).background(Color.White))
            else Text("请先让手表连接手机热点\n或与手机连接同一 Wi-Fi", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
            Text("剩余 ${((share.expiresAt - clock) / 60000).coerceAtLeast(0)} 分钟", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
            if (share.url.isNotBlank()) Text(share.url.substringBefore('#'), color = Muted, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Text("配对码 ${share.pin}", color = Mint, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
            Text("下载后在听悟手动导入\n仅在自己的热点或可信 Wi-Fi 使用", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            if (share.url.isBlank()) GlossySecondaryButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("连接 Wi-Fi", fontSize = 11.sp) }
            GlossySecondaryButton(onClick = { ctx.startService(Intent(ctx, PhoneTransferService::class.java).setAction(PhoneTransferService.STOP)) }) { Text("结束传输", color = Red, fontSize = 12.sp) }
        }
        Text("腕录 2.2.4 · 纪要在听悟查看", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun BluetoothTransferContent(selected: String, onPick: () -> Unit) {
    val ctx = LocalContext.current
    val status by BluetoothTransfers.state.collectAsState()
    var permitted by remember { mutableStateOf(Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    var peers by remember { mutableStateOf<List<BluetoothPeer>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(permitted, selected) { if (permitted) peers = runCatching { BluetoothTransfers.phones(ctx) }.getOrDefault(emptyList()) }
    if (selected.isEmpty()) {
        Text("把录音发送到已配对手机\n再用你的听悟账号导入。", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        GlossyButton(onClick = onPick, modifier = Modifier.padding(top = 14.dp)) { Text("选择录音", fontSize = 12.sp) }
    } else if (status.busy) {
        Text(status.message, color = Mint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        LinearProgressIndicator(progress = { if (status.total > 0) status.sent.toFloat() / status.total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        Text("请在手机确认蓝牙接收\n原始录音始终保留在手表", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        GlossySecondaryButton(onClick = { ctx.startService(Intent(ctx, BluetoothTransferService::class.java).setAction(BluetoothTransferService.STOP)) }) { Text("取消发送", color = Red, fontSize = 11.sp) }
    } else {
        Text(if (status.file == selected && status.message.isNotBlank()) status.message else "选择已配对的手机接收", color = if (status.done) Mint else Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        if (!permitted) GlossyButton(onClick = { if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }, modifier = Modifier.padding(top = 12.dp)) { Text("允许蓝牙连接", fontSize = 11.sp) }
        else if (peers.isEmpty()) Text("未找到已配对手机\n请先在系统蓝牙设置中连接手机", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        else peers.forEach { peer ->
            GlossyButton(onClick = {
                try { ContextCompat.startForegroundService(ctx, Intent(ctx, BluetoothTransferService::class.java).putExtra(BluetoothTransferService.NAME, selected).putExtra(BluetoothTransferService.ADDRESS, peer.address)) }
                catch (_: Exception) { error = "无法启动发送，请检查蓝牙权限" }
            }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(10.dp)) { Text(peer.name, fontSize = 11.sp) }
        }
        if (error.isNotEmpty()) Text(error, color = Red, fontSize = 10.sp)
        Text("手机接收后，在听悟导入文件\n无需更改共享手机网络的设置", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        GlossySecondaryButton(onClick = {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, PhoneTransferService::class.java).putExtra(PhoneTransferService.NAME, selected)) }
            catch (_: Exception) { error = "无法开启 Wi-Fi 传输" }
        }) { Text("同 Wi-Fi 扫码（备用）", fontSize = 10.sp) }
        GlossySecondaryButton(onClick = onPick) { Text("更换录音", fontSize = 10.sp) }
    }
}

@Composable
private fun TingwuTransferContent(selected: String, onPick: () -> Unit) {
    val ctx = LocalContext.current
    val connected by TingwuSession.connected.collectAsState()
    val work by TingwuUploads.states(ctx).collectAsState(initial = emptyList())
    val states by TingwuUploads.status.collectAsState()
    var automatic by remember { mutableStateOf(TingwuUploads.automatic(ctx)) }
    var error by remember(selected) { mutableStateOf("") }
    val jobs = work.filter { "name:$selected" in it.tags }
    val busy = jobs.any { !it.state.isFinished }
    val state = states[selected]
    Text(if (connected) "● 听悟账号已连接" else "连接后直接上传到你的听悟", color = if (connected) Mint else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    if (!connected) {
        Text("手机扫码，连接现有账号", color = Muted, fontSize = 11.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        GlossyButton(onClick = { ctx.startActivity(Intent(ctx, TingwuLoginActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Text("支付宝扫码登录", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("纪要直接在听悟查看", color = Muted, fontSize = 10.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
        if (busy) GlossySecondaryButton(onClick = {
            jobs.filter { !it.state.isFinished }.forEach { WorkManager.getInstance(ctx).cancelWorkById(it.id) }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("取消上传", color = Red, fontSize = 12.sp) }
        return
    }
    if (selected.isBlank()) {
        Text("录音留在手表，直接上传听悟\n手机只提供网络和查看结果", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        GlossyButton(onClick = onPick, modifier = Modifier.padding(top = 12.dp)) { Text("选择录音", fontSize = 12.sp) }
    } else {
        Text(state?.message?.ifBlank { "已选择一段录音" } ?: "已选择一段录音", color = if (state?.done == true) Mint else Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        if (busy) {
            LinearProgressIndicator(progress = { state?.progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            GlossySecondaryButton(onClick = { jobs.filter { !it.state.isFinished }.forEach { WorkManager.getInstance(ctx).cancelWorkById(it.id) } }) { Text("取消上传", color = Red, fontSize = 11.sp) }
        } else if (state?.done != true) {
            GlossyButton(onClick = { runCatching { TingwuUploads.enqueue(ctx, selected, state?.needsReview == true) }.onFailure { error = it.message ?: "无法开始上传" } },
                enabled = connected, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(if (state?.needsReview == true) "已检查，重新建立任务" else "上传到听悟", fontSize = 11.sp) }
            if (state?.needsReview == true) Text("请先检查手机听悟，重建会新建记录", color = Muted, fontSize = 9.sp, textAlign = TextAlign.Center)
        }
        GlossySecondaryButton(onClick = onPick) { Text("更换录音", fontSize = 10.sp) }
    }
    if (error.isNotEmpty()) Text(error, color = Red, fontSize = 10.sp, textAlign = TextAlign.Center)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).background(Card)
        .toggleable(value = automatic, enabled = connected, role = Role.Switch, onValueChange = { automatic = it; TingwuUploads.prefs(ctx).edit().putBoolean("automatic", it).apply() })
        .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("结束后自动上传", color = if (connected) Color.White else Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(checked = automatic, onCheckedChange = null, enabled = connected,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFE6FFF1), checkedTrackColor = Color(0xFF42B990)))
    }
    if (connected) GlossySecondaryButton(onClick = { ctx.startActivity(Intent(ctx, TingwuLoginActivity::class.java)) }) { Text("重新连接听悟", fontSize = 10.sp) }
}
