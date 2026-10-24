package app.klang.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.playback.AudioEngine
import app.klang.ui.K
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

enum class VisMode(val label: String) { Spectrum("spectrum"), Oscilloscope("oscilloscope"), Off("off") }

/**
 * Round speaker grille with horizontal slots split down the middle (after the RT 20 radio).
 * The slots light up orange from the centre outward with the music.
 */
@Composable
fun GrilleVisualizer(mode: VisMode, playing: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    val smooth = remember { FloatArray(AudioEngine.METER_BANDS) }
    val isPlaying by rememberUpdatedState(playing)
    Canvas(
        modifier
            .clip(RoundedCornerShape(50))
            .semantics { contentDescription = "Visualizer: ${mode.label}. Tap to change." }
            .clickable(onClick = onTap)
    ) {
        frame // redraw every frame
        val rows = AudioEngine.METER_BANDS
        val w = size.minDimension
        val r = w / 2f * 0.97f
        val c = center
        val slotH = w / (rows * 2f + 1f)
        val gap = w * 0.035f
        val live = AudioEngine.audioIsLive() && isPlaying
        val t = frame / 1_000_000_000.0
        for (i in 0 until rows) {
            val y = c.y - r + slotH * (i * 2 + 1)
            val dy = abs(y + slotH / 2f - c.y)
            val hw = sqrt((r * r - dy * dy).coerceAtLeast(0f)) - slotH * 0.6f
            if (hw <= gap) continue
            val len = hw - gap / 2f
            val cr = CornerRadius(slotH / 2f)
            drawRoundRect(K.hole, Offset(c.x - gap / 2f - len, y), Size(len, slotH), cr)
            drawRoundRect(K.hole, Offset(c.x + gap / 2f, y), Size(len, slotH), cr)

            // bottom row = bass
            val band = rows - 1 - i
            val target = if (live) AudioEngine.levels[band] else 0f
            smooth[band] += (target - smooth[band]) * if (target > smooth[band]) 0.5f else 0.12f
            when (mode) {
                VisMode.Spectrum -> {
                    val l = len * smooth[band]
                    if (l > slotH * 0.6f) {
                        drawRoundRect(K.orange, Offset(c.x - gap / 2f - l, y), Size(l, slotH), cr)
                        drawRoundRect(K.orange, Offset(c.x + gap / 2f, y), Size(l, slotH), cr)
                    }
                }
                VisMode.Oscilloscope -> {
                    val wave = AudioEngine.wave
                    val v = if (live) wave[(i * wave.size / rows).coerceIn(0, wave.size - 1)] else 0f
                    val pos = (0.5f + v * 2.2f).coerceIn(0f, 1f)
                    val dot = slotH
                    if (live) {
                        drawRoundRect(K.orange, Offset(c.x - gap / 2f - len * pos - dot / 2f, y), Size(dot, slotH), cr)
                        drawRoundRect(K.orange, Offset(c.x + gap / 2f + len * pos - dot / 2f, y), Size(dot, slotH), cr)
                    }
                }
                VisMode.Off -> Unit
            }
        }
    }
}

// ------------------------------------------------------------------ 7-segment display

private val SEGMENTS = mapOf(
    'a' to floatArrayOf(3f, 0f, 14f, 3f), 'd' to floatArrayOf(3f, 27f, 14f, 3f), 'g' to floatArrayOf(3f, 13.5f, 14f, 3f),
    'f' to floatArrayOf(0f, 2f, 3f, 13f), 'b' to floatArrayOf(17f, 2f, 3f, 13f),
    'e' to floatArrayOf(0f, 15f, 3f, 13f), 'c' to floatArrayOf(17f, 15f, 3f, 13f),
)
private val DIGITS = listOf("abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg")

/** Braun clock style LCD digits: dark segments with faint unlit segments. Text like "01:23" or "-03:35". */
@Composable
fun SevenSegment(text: String, modifier: Modifier = Modifier, height: Dp = 40.dp, on: Color = K.seg, ghost: Color = K.ghost) {
    Canvas(modifier.height(height).fillMaxWidth()) {
        val u = size.height / 31f
        var x = 0f
        for (ch in text) {
            when {
                ch.isDigit() -> {
                    val lit = DIGITS[ch - '0']
                    for ((k, s) in SEGMENTS) {
                        drawRoundRect(
                            if (k in lit) on else ghost,
                            Offset(x + s[0] * u, 0.5f * u + s[1] * u),
                            Size(s[2] * u, s[3] * u),
                            CornerRadius(1.5f * u)
                        )
                    }
                    x += 24f * u
                }
                ch == ':' -> {
                    drawCircle(on, 2.4f * u, Offset(x + 3f * u, 10f * u))
                    drawCircle(on, 2.4f * u, Offset(x + 3f * u, 21f * u))
                    x += 9f * u
                }
                ch == '-' -> {
                    drawRoundRect(on, Offset(x, 14f * u), Size(10f * u, 3f * u), CornerRadius(1f * u))
                    x += 14f * u
                }
                else -> x += 12f * u
            }
        }
    }
}

// ------------------------------------------------------------------ tuning scale (position)

/**
 * Glowing radio tuning window with a red needle. Minutes on the top scale, track numbers on the
 * bottom scale. Drag or tap to seek.
 */
@Composable
fun TuningScale(
    progress: Float,
    durationMs: Long,
    trackIndex: Int,
    trackCount: Int,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val seek by rememberUpdatedState(onSeek)
    val shown = if (dragging) dragValue else progress
    val numStyle = TextStyle(fontFamily = K.mono, fontSize = 9.sp, color = K.scaleInk)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Position. Drag to seek." }
            .pointerInput(Unit) {
                fun frac(x: Float) = ((x / size.width - 0.04f) / 0.92f).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragValue = frac(it.x) },
                    onDragEnd = { dragging = false; seek(dragValue) },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    dragValue = frac(change.position.x)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { seek(((it.x / size.width - 0.04f) / 0.92f).coerceIn(0f, 1f)) }
            }
    ) {
        drawRect(Brush.verticalGradient(listOf(K.glow, K.glow2)))
        drawRect(Brush.radialGradient(listOf(Color(0x33F08C3C), Color.Transparent), center = center, radius = size.width * 0.6f))
        val x0 = size.width * 0.04f
        val x1 = size.width * 0.96f
        val totalSec = (durationMs / 1000L).coerceAtLeast(1L)
        var s = 0L
        while (s <= totalSec) {
            val x = x0 + (x1 - x0) * s / totalSec
            val major = s % 60L == 0L
            drawLine(
                if (major) K.scaleInk else K.scaleInk2,
                Offset(x, (if (major) 14 else 18).dp.toPx()),
                Offset(x, (if (major) 30 else 26).dp.toPx()),
                if (major) 1.2.dp.toPx() else 0.8.dp.toPx()
            )
            if (major) {
                val lay = measurer.measure((s / 60L).toString(), numStyle)
                drawText(lay, topLeft = Offset(x - lay.size.width / 2f, 1.dp.toPx()))
            }
            s += if (totalSec > 1200) 60 else 10
        }
        val baseY = 40.dp.toPx()
        drawLine(K.scaleInk2, Offset(x0, baseY), Offset(x1, baseY), 0.8.dp.toPx())
        val n = trackCount.coerceIn(1, 24)
        for (i in 0 until n) {
            val x = if (n == 1) x0 else x0 + (x1 - x0) * i / (n - 1)
            val cur = i == trackIndex
            drawLine(if (cur) K.needle else K.scaleInk2, Offset(x, baseY - 3.dp.toPx()), Offset(x, baseY + 3.dp.toPx()), (if (cur) 1.6 else 0.8).dp.toPx())
            if (n <= 14 || i % 2 == 0 || cur) {
                val lay = measurer.measure((i + 1).toString().padStart(2, '0'), numStyle.copy(fontSize = 8.5.sp, color = if (cur) K.needle else K.scaleInk))
                drawText(lay, topLeft = Offset(x - lay.size.width / 2f, baseY + 5.dp.toPx()))
            }
        }
        // needle
        val nx = x0 + (x1 - x0) * shown.coerceIn(0f, 1f)
        drawRect(K.needle.copy(alpha = 0.25f), Offset(nx - 3.dp.toPx(), 4.dp.toPx()), Size(6.dp.toPx(), size.height - 8.dp.toPx()))
        drawRect(K.needle, Offset(nx - 1.dp.toPx(), 4.dp.toPx()), Size(2.dp.toPx(), size.height - 8.dp.toPx()))
        // inner shadow
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent), endY = 4.dp.toPx()))
    }
}
