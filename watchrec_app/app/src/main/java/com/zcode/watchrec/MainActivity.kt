package com.zcode.watchrec

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

// 配色(参考扁平分段圆环设计)
private val BG = Color(0xFF0D0D0D)
private val MINT_DEEP = Color(0xFF4EC99B)
private val MINT_LIGHT = Color(0xFF7FE0B8)
private val MINT = Color(0xFF5FD9A4)
private val CORAL = Color(0xFFF26B6B)
private val TRACK_OFF = Color(0xFF26262B)
private val CARD = Color(0xFF1A1A1F)
private val CARD_ON = Color(0xFF20242A)
private val TXT_MAIN = Color(0xFFFFFFFF)
private val TXT_SUB = Color(0xFF9E9E9E)
private val TXT_DIM = Color(0xFF6E6E73)
private val LABEL_ON = Color(0xFF111111)

private const val PREFS = "wl"
private const val KEY_ADDR = "panel_addr"
private const val KEY_UPLOADED = "uploaded"

private fun hasMic(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** 手动上传一条录音到电脑面板(仅传文件,绝不触发解析) */
private fun uploadToPanel(
    addr: String, file: File, onDone: (Boolean, String) -> Unit,
) {
    thread {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://$addr/api/watch_push").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            val boundary = "----wl${System.currentTimeMillis()}"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { os ->
                os.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                        "filename=\"${file.name}\"\r\nContent-Type: audio/mp4\r\n\r\n").toByteArray()
                )
                file.inputStream().use { it.copyTo(os) }
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            onDone(code == 200, body.take(80))
        } catch (e: Exception) {
            onDone(false, e.message?.take(80) ?: "网络错误")
        } finally {
            conn?.disconnect()
        }
    }
}

/** 测试面板连通性 */
private fun pingPanel(addr: String, onDone: (Boolean) -> Unit) {
    thread {
        try {
            val conn = URL("http://$addr/api/state").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            onDone(conn.responseCode == 200)
            conn.disconnect()
        } catch (_: Exception) {
            onDone(false)
        }
    }
}

@Composable
fun App() {
    Surface(modifier = Modifier.fillMaxSize(), color = BG) {
        val pager = rememberPagerState { 3 }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> RecordPage()
                1 -> FilesPage()
                else -> SettingsPage()
            }
        }
    }
}

/* ============================== 第 1 页: 录音 ============================== */

@Composable
fun RecordPage() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasMic(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasMic(ctx) }

    fun askPerms() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(need.toTypedArray())
    }

    LaunchedEffect(Unit) {
        RecorderState.refreshFiles(ctx)
        if (!granted) askPerms()
    }

    val recording by RecorderState.isRecording.collectAsState()
    val startedAt by RecorderState.startedAt.collectAsState()
    val err by RecorderState.error.collectAsState()
    val filesSummary by RecorderState.filesSummary.collectAsState()

    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(recording) {
        while (recording) {
            tick = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val secs = if (recording && startedAt > 0) ((tick - startedAt) / 1000L).coerceAtLeast(0L) else 0L
    val timeText = "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
    val minuteP = (secs % 60) / 60f

    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(recording) {
        while (recording) {
            blink = !blink
            delay(600)
        }
        blink = true
    }

    LaunchedEffect(recording) {
        if (!recording) {
            delay(400)
            RecorderState.refreshFiles(ctx)
        }
    }

    fun toggleStart() {
        if (!granted) {
            askPerms()
            return
        }
        if (!recording) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java))
        }
    }

    fun toggleStop() {
        if (recording) {
            ctx.startService(
                Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // 圆环按钮层(上半弧=开始,下半弧=结束)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(recording) {
                    detectTapGestures { off ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val d = off - c
                        val dist = d.getDistance()
                        val inner = minOf(size.width, size.height) * 0.26f
                        if (dist > inner) {
                            if (d.y < 0) toggleStart() else toggleStop()
                        }
                    }
                }
        ) {
            drawRing(recording = recording, minuteP = if (recording) minuteP else 0f)
        }

        // 左上角状态
        Text(
            text = if (recording) "● REC" else "腕录",
            color = if (recording) (if (blink) CORAL else CORAL.copy(alpha = 0.35f)) else TXT_DIM,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 43.dp, top = 44.dp),
        )

        // 中央文字堆叠
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                timeText,
                fontSize = 24.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (recording) TXT_MAIN else TXT_MAIN.copy(alpha = 0.55f),
            )
            Text(
                text = when {
                    err != null -> err!!.take(10)
                    recording -> "正在录音"
                    else -> "点击上方圆环开始"
                },
                color = if (err != null) CORAL else TXT_SUB,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (!recording && filesSummary.isNotEmpty()) {
                Text(
                    filesSummary,
                    color = MINT,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // 底部: 左滑提示
        Text(
            text = "‹ 左滑 管理录音",
            color = TXT_DIM,
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
        )
    }
}

/* ============================== 第 2 页: 录音列表(手动上传/删除) ============================== */

@Composable
fun FilesPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { RecorderState.refreshFiles(ctx) }

    val files by RecorderState.files.collectAsState()
    val recording by RecorderState.isRecording.collectAsState()
    val summary by RecorderState.filesSummary.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var statusOk by remember { mutableStateOf(true) }
    val sp = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var uploaded by remember {
        mutableStateOf(sp.getStringSet(KEY_UPLOADED, emptySet())?.toSet() ?: emptySet())
    }

    LaunchedEffect(confirmDel) {
        if (confirmDel != null) {
            delay(3000)
            confirmDel = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 30.dp, end = 30.dp, top = 40.dp, bottom = 26.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("录音列表", color = TXT_MAIN, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (files.isEmpty()) "空" else summary,
                color = MINT, fontSize = 10.sp,
            )
        }
        if (recording) {
            Text(
                "录音进行中 · 停止后可管理",
                color = CORAL, fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (files.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    "暂无录音\n回第一页点「开始」",
                    color = TXT_DIM, fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(files, key = { it.name }) { f ->
                    val isOpen = expanded == f.name
                    val confirming = confirmDel == f.name
                    val isUploading = uploading == f.name
                    val isDone = f.name in uploaded

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    confirming -> Color(0x2EF26B6B)
                                    isOpen -> CARD_ON
                                    else -> CARD
                                },
                                RoundedCornerShape(10.dp),
                            ),
                    ) {
                        // 行主体: 点按展开/收起
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = if (isOpen) null else f.name; confirmDel = null }
                                .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isDone) {
                                        Text("✓ ", color = MINT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        prettyTime(f), color = TXT_MAIN,
                                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Text(
                                    "${fmtSize(f.size)} · 约 ${fmtDur(f.size)}" +
                                        if (isDone) " · 已上传电脑" else "",
                                    color = TXT_SUB, fontSize = 9.sp,
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                                Text(
                                    f.name,
                                    color = TXT_DIM, fontSize = 8.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (isOpen) "▾" else "▸",
                                color = TXT_DIM, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        // 展开的操作行
                        if (isOpen) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x14FFFFFF), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    when {
                                        isUploading -> "上传中…"
                                        isDone -> "⬆ 再传一次"
                                        else -> "⬆ 上传到电脑"
                                    },
                                    color = if (isUploading) TXT_DIM else MINT,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable(enabled = !isUploading) {
                                            val addr = sp.getString(KEY_ADDR, "") ?: ""
                                            if (addr.isBlank()) {
                                                statusOk = false
                                                statusMsg = "先到「设置」填面板地址"
                                                return@clickable
                                            }
                                            val file = File(ctx.getExternalFilesDir(null), f.name)
                                            uploading = f.name
                                            statusOk = true
                                            statusMsg = "正在上传 ${f.name} …"
                                            uploadToPanel(addr, file) { ok, msg ->
                                                scope.launch {
                                                    uploading = null
                                                    if (ok) {
                                                        uploaded = uploaded + f.name
                                                        sp.edit()
                                                            .putStringSet(KEY_UPLOADED, uploaded)
                                                            .apply()
                                                        statusOk = true
                                                        statusMsg = "✓ 已上传到电脑"
                                                    } else {
                                                        statusOk = false
                                                        statusMsg = "上传失败: $msg"
                                                    }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 7.dp),
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (confirming) "确认删" else "✕ 删除",
                                    color = if (confirming) CORAL else TXT_SUB,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            if (confirming) {
                                                if (RecorderState.deleteFile(ctx, f.name)) {
                                                    confirmDel = null
                                                    expanded = null
                                                }
                                            } else {
                                                confirmDel = f.name
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (statusMsg.isNotEmpty()) {
            Text(
                statusMsg,
                color = if (statusOk) MINT else CORAL,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Text(
            "右滑 录音 · 左滑 设置 ›",
            color = TXT_DIM, fontSize = 9.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
        )
    }
}

/* ============================== 第 3 页: 设置 ============================== */

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var addr by remember { mutableStateOf(sp.getString(KEY_ADDR, "") ?: "") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 34.dp, end = 34.dp, top = 40.dp, bottom = 26.dp),
    ) {
        Text("设置", color = TXT_MAIN, fontSize = 15.sp, fontWeight = FontWeight.Bold)

        Text(
            "电脑面板地址",
            color = TXT_SUB, fontSize = 11.sp,
            modifier = Modifier.padding(top = 14.dp),
        )
        BasicTextField(
            value = addr,
            onValueChange = {
                addr = it
                sp.edit().putString(KEY_ADDR, it.trim()).apply()
            },
            singleLine = true,
            textStyle = TextStyle(
                color = TXT_MAIN, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(MINT),
            decorationBox = { inner ->
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CARD, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        if (addr.isEmpty()) {
                            Text("192.168.1.5:8733", color = TXT_DIM, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        inner()
                    }
                    Text(
                        "在电脑面板「三步用起来」卡片里查看;\n手表要与电脑连同一 Wi-Fi。",
                        color = TXT_DIM, fontSize = 8.5.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        )

        // 测试连接
        Text(
            when {
                testing -> "测试中…"
                testResult == null -> ""
                testResult!!.first -> "✓ 连接成功"
                else -> "✗ 连不上,检查地址/同一 Wi-Fi/防火墙"
            },
            color = when {
                testResult?.first == true -> MINT
                testResult != null -> CORAL
                else -> TXT_DIM
            },
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            if (testing) "…" else "测试连接",
            color = if (testing) TXT_DIM else MINT,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(enabled = !testing) {
                    val a = addr.trim()
                    if (a.isEmpty()) {
                        testResult = false to "empty"
                        return@clickable
                    }
                    testing = true
                    pingPanel(a) { ok ->
                        testing = false
                        testResult = ok to ""
                    }
                }
                .padding(vertical = 6.dp),
        )

        // 清空"已上传"标记
        Text(
            "清除已上传标记",
            color = TXT_SUB, fontSize = 10.sp,
            modifier = Modifier
                .clickable {
                    sp.edit().remove(KEY_UPLOADED).apply()
                    testResult = null
                }
                .padding(top = 12.dp, bottom = 6.dp),
        )

        Text(
            "腕录 v1.5 · 手动上传,绝不自动",
            color = TXT_DIM, fontSize = 8.5.sp,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/* ============================== 工具 ============================== */

/** REC_20261022_203202.m4a → "10-22 20:32";解析不了就用文件时间 */
private fun prettyTime(f: RecFile): String {
    val m = Regex("REC_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})").find(f.name)
    if (m != null) {
        val (y, mo, d, h, mi) = m.destructured
        return "$mo-$d $h:$mi"
    }
    return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(f.modified))
}

private fun fmtSize(bytes: Long): String = "%.1f MB".format(bytes / 1048576.0)

/** AAC 64kbps ≈ 8000 字节/秒 */
private fun fmtDur(bytes: Long): String {
    val s = bytes / 8000
    return "%d:%02d".format(s / 60, s % 60)
}

/* ============================== 圆环绘制 ============================== */

/** 扁平分段圆环:粗弧按键(标签嵌弧内) + 内圈细进度弧
 *  注意:Stroke 以路径为中线向两侧延伸,半径一律按"弧带中心线"理解 */
private fun DrawScope.drawRing(recording: Boolean, minuteP: Float) {
    val m = size.minDimension
    val stroke = m * 0.125f                 // 弧带厚度
    val pathR = m * 0.4115f                 // 弧带中心线半径
    val cx = size.width / 2f
    val cy = size.height / 2f
    val topLeft = Offset(cx - pathR, cy - pathR)
    val arcSize = Size(pathR * 2, pathR * 2)

    // 上半弧「开始」: 待机=薄荷绿渐变,录音中=暗灰(禁用)
    drawArc(
        brush = if (recording) SolidColor(TRACK_OFF)
        else androidx.compose.ui.graphics.Brush.sweepGradient(
            listOf(MINT_DEEP, MINT_LIGHT, MINT_DEEP)
        ),
        startAngle = -90f - 82f, sweepAngle = 164f, useCenter = false,
        topLeft = topLeft, size = arcSize,
        style = Stroke(stroke, cap = StrokeCap.Butt),
    )
    // 下半弧「结束」: 录音中=珊瑚红,待机=暗灰(禁用)
    drawArc(
        color = if (recording) CORAL else TRACK_OFF,
        startAngle = 90f - 82f, sweepAngle = 164f, useCenter = false,
        topLeft = topLeft, size = arcSize,
        style = Stroke(stroke, cap = StrokeCap.Butt),
    )

    // 弧内标签(黑字,嵌在弧带中心线上)
    drawArcLabel("开始", Offset(cx, cy - pathR), m * 0.072f, on = !recording)
    drawArcLabel("结束", Offset(cx, cy + pathR), m * 0.072f, on = recording)

    // 内圈细进度弧(分钟进度)
    if (recording && minuteP > 0.003f) {
        val pStroke = m * 0.0086f
        val pR = pathR - stroke / 2f - m * 0.022f - pStroke / 2f
        drawArc(
            color = MINT_LIGHT,
            startAngle = -90f, sweepAngle = 360f * minuteP, useCenter = false,
            topLeft = Offset(cx - pR, cy - pR),
            size = Size(pR * 2, pR * 2),
            style = Stroke(pStroke, cap = StrokeCap.Butt),
        )
    }
}

private fun DrawScope.drawArcLabel(text: String, center: Offset, textSize: Float, on: Boolean) {
    val color = if (on) LABEL_ON else Color(0xFF63636A)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()
        )
        this.textSize = textSize
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        text, center.x, center.y - (paint.descent() + paint.ascent()) / 2f, paint
    )
}
