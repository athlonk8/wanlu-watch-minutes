package com.zcode.watchrec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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

    LaunchedEffect(recording) {
        if (!recording) {
            delay(400)
            RecorderState.refreshFiles(ctx)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(timeText, fontSize = 26.sp, fontFamily = FontFamily.Monospace, color = Color.White)
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    err != null -> err!!
                    recording -> "正在录音"
                    else -> "就绪"
                },
                color = if (err != null) Color(0xFFFF8A80) else Color(0xFF8FA3BF),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                RingButton(
                    glyph = "\u25B6", label = "开始",
                    active = !recording,
                    ringColor = Color(0xFF4DA3FF), glyphColor = Color(0xFFBBDDFF),
                ) {
                    if (!granted) {
                        askPerms()
                        return@RingButton
                    }
                    if (!recording) {
                        ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java))
                    }
                }
                RingButton(
                    glyph = "\u25A0", label = "结束",
                    active = recording,
                    ringColor = Color(0xFFFF5A56), glyphColor = Color(0xFFFFD9D7),
                    glow = recording,
                    progress = { (secs % 60) / 60f },
                ) {
                    if (recording) {
                        ctx.startService(
                            Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            if (!recording && lastFile != null) {
                Text(lastFile!!, fontSize = 9.sp, color = Color(0xFF6E7B8C), textAlign = TextAlign.Center,
                    maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(filesSummary, fontSize = 10.sp, color = Color(0xFF8FA3BF))
            }
        }
    }
}

/** 圆环按键:轨道环 + 进度弧 + 呼吸光晕,适配圆屏 */
@Composable
fun RingButton(
    glyph: String,
    label: String,
    active: Boolean,
    ringColor: Color,
    glyphColor: Color,
    glow: Boolean = false,
    progress: () -> Float = { 0f },
    onClick: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glowAlpha by pulse.animateFloat(0.06f, 0.3f, infiniteRepeatable(tween(900)), label = "glow")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(86.dp)
                .clickable(enabled = active) { onClick() },
        ) {
            Canvas(Modifier.size(86.dp)) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2 + 3.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)

                if (glow) {
                    drawCircle(color = ringColor.copy(alpha = glowAlpha), radius = size.minDimension / 2 - stroke)
                }
                // 轨道环(未激活时低透明度)
                drawArc(
                    color = ringColor.copy(alpha = if (active) 0.9f else 0.32f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                // 进度弧(与轨道同宽,白色覆盖)
                val p = progress().coerceIn(0f, 1f)
                if (active && p > 0.004f) {
                    drawArc(
                        color = Color.White,
                        startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(glyph, fontSize = 20.sp, color = glyphColor.copy(alpha = if (active) 1f else 0.55f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = if (active) Color(0xFFDDE6F2) else Color(0xFF56627A),
        )
    }
}
