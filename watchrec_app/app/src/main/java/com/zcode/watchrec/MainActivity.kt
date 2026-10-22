package com.zcode.watchrec

import android.Manifest
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

// 配色(参考扁平分段圆环设计)
private val BG = Color(0xFF0D0D0D)
private val MINT = Color(0xFF5FD9A4)          // 开始/主色
private val MINT_DEEP = Color(0xFF4EC99B)
private val MINT_LIGHT = Color(0xFF7FE0B8)
private val CORAL = Color(0xFFF26B6B)         // 停止
private val TRACK_OFF = Color(0xFF26262B)     // 未激活弧
private val TXT_MAIN = Color(0xFFFFFFFF)
private val TXT_SUB = Color(0xFF9E9E9E)
private val TXT_DIM = Color(0xFF6E6E73)
private val LABEL_ON = Color(0xFF111111)      // 弧内标签(亮弧用黑字)

private fun hasMic(ctx: android.content.Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun App() {
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
    val lastFile by RecorderState.lastFile.collectAsState()

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

    Surface(modifier = Modifier.fillMaxSize(), color = BG) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ---- 圆环按钮层(上半弧=开始,下半弧=结束) ----
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

            // ---- 左上角状态(呼应参考图 1/10 的小字) ----
            Text(
                text = if (recording) "● REC" else "腕录",
                color = if (recording) (if (blink) CORAL else CORAL.copy(alpha = 0.35f)) else TXT_DIM,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 43.dp, top = 44.dp),
            )

            // ---- 中央文字堆叠 ----
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

            // ---- 底部:最近文件(仅待机) ----
            if (!recording && lastFile != null) {
                Text(
                    lastFile!!,
                    color = TXT_DIM,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 34.dp, start = 40.dp, end = 40.dp),
                )
            }
        }
    }
}

/** 扁平分段圆环:粗弧按键(标签嵌弧内) + 内圈细进度弧
 *  注意:Stroke 以路径为中线向两侧延伸,半径一律按"弧带中心线"理解 */
private fun DrawScope.drawRing(recording: Boolean, minuteP: Float) {
    val m = size.minDimension
    val stroke = m * 0.125f                 // 弧带厚度
    val pathR = m * 0.4115f                 // 弧带中心线半径(外沿=m/2-0.026m,不裁屏)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val topLeft = Offset(cx - pathR, cy - pathR)
    val arcSize = Size(pathR * 2, pathR * 2)

    // 上半弧「开始」: 待机=薄荷绿渐变,录音中=暗灰(禁用)
    drawArc(
        brush = if (recording) androidx.compose.ui.graphics.SolidColor(TRACK_OFF)
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
    drawArcLabel(
        "开始", Offset(cx, cy - pathR),
        textSize = m * 0.072f, on = !recording,
    )
    drawArcLabel(
        "结束", Offset(cx, cy + pathR),
        textSize = m * 0.072f, on = recording,
    )

    // 内圈细进度弧(分钟进度,薄荷绿),与主环之间留空隙
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

private fun DrawScope.drawArcLabel(text: String, center: Offset, textSize: Float, on: Boolean) {    val color = if (on) LABEL_ON else Color(0xFF63636A)
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
