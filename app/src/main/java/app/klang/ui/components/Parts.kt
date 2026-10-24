package app.klang.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.ui.K
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ tiles

@Composable
fun Tile(
    modifier: Modifier = Modifier,
    color: Color = K.tile,
    padding: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    var m = modifier.clip(shape).background(color, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun LabelRow(left: String, right: String? = null, modifier: Modifier = Modifier, rightContent: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(left, style = K.label, modifier = Modifier.weight(1f))
        if (right != null) Text(right, style = K.value)
        rightContent?.invoke()
    }
}

@Composable
fun Lamp(on: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        val r = 3.5.dp.toPx()
        if (on) {
            drawCircle(K.orange.copy(alpha = 0.18f), r + 2.dp.toPx())
            drawCircle(K.orange, r)
        } else drawCircle(K.ink3, r)
    }
}

/** Light clock-style LCD, set into the shell with a thin bezel. */
@Composable
fun LcdBox(modifier: Modifier = Modifier, padding: Dp = 9.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier
            .background(K.line, RoundedCornerShape(14.dp))
            .padding(1.dp)
            .background(K.shell, RoundedCornerShape(13.dp))
            .padding(3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(K.lcd, K.lcd2)))
            .drawWithContent {
                drawContent()
                // inner top shadow, like a recessed glass
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent),
                        endY = 5.dp.toPx()
                    )
                )
            }
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun WoodCheek(modifier: Modifier = Modifier) {
    Canvas(modifier.width(12.dp).fillMaxHeight()) {
        drawRect(Brush.horizontalGradient(listOf(K.wood2, K.wood, K.wood2)))
        var x = 1f
        while (x < size.width) {
            drawLine(Color.Black.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f)
            x += 3.dp.toPx() * (0.7f + (x % 5f) / 10f)
        }
    }
}

// ------------------------------------------------------------------ keys

/** Keycap: light face, a lip underneath and travel when pressed. */
@Composable
fun Key(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orange: Boolean = false,
    dark: Boolean = false,
    corner: Dp = 9.dp,
    description: String? = null,
    content: @Composable BoxScope.(Color) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(corner)
    val face = when {
        orange -> listOf(K.orangeLight, K.orange)
        dark -> listOf(K.ink, Color(0xFF111110))
        else -> listOf(K.keyHi, K.keyLo)
    }
    val lip = when {
        orange -> K.orangeDark
        dark -> Color(0xFF000000)
        else -> K.keyLip
    }
    val fg = if (orange || dark) Color.White else K.ink
    val travel = if (pressed) 1.5.dp else 0.dp
    Box(
        modifier
            .semantics { if (description != null) contentDescription = description }
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
    ) {
        // lip
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 3.dp)
                .shadow(if (pressed) 0.5.dp else 1.5.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .background(lip, shape)
        )
        // face
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 2.5.dp)
                .offset(y = travel)
                .background(Brush.verticalGradient(face), shape)
                .border(0.5.dp, Color.White.copy(alpha = if (orange || dark) 0.15f else 0.8f), shape),
            contentAlignment = Alignment.Center
        ) { content(fg) }
    }
}

@Composable
fun GlyphKey(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, orange: Boolean = false, description: String, iconSize: Dp = 18.dp) {
    Key(onClick, modifier, orange = orange, description = description) { fg -> GlyphIcon(glyph, fg, iconSize) }
}

@Composable
fun TextKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, orange: Boolean = false) {
    Key(onClick, modifier, orange = orange, dark = active) { fg ->
        Text(text, style = K.body.copy(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}

/** Row of keys joined like the piano keys on a record player. */
@Composable
fun PianoRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .background(K.line, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        content = content
    )
}

// ------------------------------------------------------------------ radio push button

@Composable
fun PushButton(on: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = K.label.copy(fontSize = 10.5.sp))
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "$label ${if (on) "on" else "off"}" }
                .clickable(role = Role.Switch, onClick = onClick)
        ) {
            val c = center
            val outer = size.minDimension / 2f
            drawCircle(K.line, outer)
            drawCircle(Color.White, outer - 1.dp.toPx())
            drawCircle(K.tile2, outer - 3.dp.toPx())
            drawCircle(K.shell, outer - 4.dp.toPx())
            val r = (outer - 7.dp.toPx()) * if (on) 0.9f else 1f
            drawCircle(Color.Black.copy(alpha = if (on) 0.12f else 0.22f), r, c + Offset(0f, if (on) 1.dp.toPx() else 2.5.dp.toPx()))
            drawCircle(
                Brush.radialGradient(listOf(K.sageLight, K.sage, K.sageDark), center = c + Offset(-r * 0.25f, -r * 0.35f), radius = r * 1.5f),
                r, c
            )
            if (on) drawCircle(K.orange, outer - 2.dp.toPx(), style = Stroke(1.6.dp.toPx()))
        }
    }
}

// ------------------------------------------------------------------ knobs

enum class KnobStyle { White, Sage }

/**
 * Rotary knob. [value] is 0..1 mapped to -135°..+135°. Drag up/down to turn.
 */
@Composable
fun Knob(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    style: KnobStyle = KnobStyle.White,
    ticks: Int = 30,
    majorEvery: Int = 5,
    description: String,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectDragGestures { pc, drag ->
                    pc.consume()
                    val delta = (-drag.y + drag.x * 0.3f) / (size.height * 1.6f)
                    change((current + delta).coerceIn(0f, 1f))
                }
            }
    ) {
        val r = size.minDimension / 2f
        val c = center
        // tick dots
        for (i in 0..ticks) {
            val a = Math.toRadians((-135.0 + i * 270.0 / ticks))
            val major = i % majorEvery == 0
            val rr = r * 0.94f
            drawCircle(
                if (major) Color(0xFF3A3A37) else K.ink3,
                if (major) 1.6.dp.toPx() else 1.dp.toPx(),
                Offset(c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat())
            )
        }
        val body = if (style == KnobStyle.White) r * 0.66f else r * 0.74f
        // shadow
        drawCircle(Color.Black.copy(alpha = 0.22f), body, c + Offset(0f, body * 0.12f))
        if (style == KnobStyle.White) {
            drawCircle(Color(0xFF3A3A37), body + 1.2.dp.toPx(), c)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFEBE9E4)), center = c + Offset(0f, -body * 0.4f), radius = body * 1.4f), body, c)
            drawCircle(K.orange, body * 0.28f, c, style = Stroke(2.dp.toPx()))
        } else {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFD3D8D3), K.sage, K.sageDark), center = c + Offset(-body * 0.25f, -body * 0.35f), radius = body * 1.5f), body, c)
        }
        rotate(-135f + current * 270f, c) {
            if (style == KnobStyle.White) {
                drawRoundRect(
                    K.ink,
                    topLeft = Offset(c.x - 2.5.dp.toPx(), c.y - body * 0.88f),
                    size = androidx.compose.ui.geometry.Size(5.dp.toPx(), body * 0.26f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                )
            } else {
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(c.x - 1.5.dp.toPx(), c.y - body * 0.85f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), body * 0.4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
                )
            }
        }
    }
}

/** Round outline icon used on tool tiles. */
@Composable
fun OutlineIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = K.ink) {
    Box(
        modifier
            .size(30.dp)
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center
    ) { GlyphIcon(glyph, color, 16.dp) }
}
