package com.zcode.watchrec

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class ButtonTone(val face: List<Color>, val edge: Color, val text: Color) {
    Mint(listOf(Color(0xFFD7FFE9), Color(0xFF8AF1C5), Color(0xFF43C59E)), Color(0xFF19775F), Color(0xFF103D32)),
    Coral(listOf(Color(0xFFFFE1CF), Color(0xFFFFA391), Color(0xFFEF746F)), Color(0xFFA1424E), Color(0xFF602B33)),
    Slate(listOf(Color(0xFF536C7B), Color(0xFF324A59), Color(0xFF233642)), Color(0xFF101D25), Color(0xFFEDF9F7))
}

/** A generous touch target with a painted bevel and visible, accessible pressed state. */
@Composable
internal fun GlossyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ButtonTone = ButtonTone.Mint,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(90), label = "button depth")
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
            .alpha(if (enabled) 1f else .42f)
            .shadow(if (pressed) 2.dp else 7.dp, shape, clip = false)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val depth = 5.dp.toPx()
            val offset = travel * 3.dp.toPx()
            val height = size.height - depth
            val radius = CornerRadius(minOf(size.width / 2, height / 2))
            drawRoundRect(tone.edge, cornerRadius = radius)
            drawRoundRect(Brush.verticalGradient(tone.face, startY = offset, endY = height + offset),
                topLeft = Offset(0f, offset), size = Size(size.width, height), cornerRadius = radius)
            drawRoundRect(Color.White.copy(alpha = .35f), topLeft = Offset(1.dp.toPx(), offset + 1.dp.toPx()),
                size = Size(size.width - 2.dp.toPx(), height - 2.dp.toPx()), cornerRadius = radius, style = Stroke(1.dp.toPx()))
            // Clip the enamel highlights to the face, including during the press animation.
            clipPath(Path().apply { addRoundRect(RoundRect(Rect(0f, offset, size.width, offset + height), radius)) }) {
                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .50f), Color.White.copy(alpha = .03f)),
                    startY = offset, endY = offset + height * .48f),
                    topLeft = Offset(5.dp.toPx(), offset + 3.dp.toPx()),
                    size = Size((size.width - 10.dp.toPx()).coerceAtLeast(0f), height * .42f),
                    cornerRadius = CornerRadius(height * .28f))
                drawOval(Color.White.copy(alpha = .58f), topLeft = Offset(size.width * .19f, offset + 6.dp.toPx()),
                    size = Size(minOf(14.dp.toPx(), size.width * .13f), 2.dp.toPx()))
            }
        }
        CompositionLocalProvider(LocalContentColor provides tone.text,
            LocalTextStyle provides TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)) {
            Row(Modifier.offset(y = (travel * 3f - 2.5f).dp).padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                content = content)
        }
    }
}

@Composable
internal fun GlossySecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) = GlossyButton(onClick, modifier, enabled, ButtonTone.Slate, contentPadding, content)
