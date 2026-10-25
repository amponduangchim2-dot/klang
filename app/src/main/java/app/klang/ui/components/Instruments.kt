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
 * Speaker grille of round holes (after the OP-1). It doubles as the visualizer:
 * holes light up orange with the music (spectrum bars or the waveform).
 */
@Composable
fun GrilleVisualizer(mode: VisMode, playing: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    val smooth = remember { FloatArray(AudioEngine.METER_BANDS) }
    val isPlaying by rememberUpdatedState(playing)
    Canvas(
        modifier
            .semantics { contentDescription = "Speaker and visualizer: ${mode.label}. Tap to change." }
            .clickable(onClick = onTap)
    ) {
        frame // redraw every frame
        // OP-1 style speaker: a grid of round holes in a rounded square, the corners left solid
        val pitch = 6.4.dp.toPx()
        val hole = 1.55.dp.toPx()
        val cols = ((size.width - hole * 2) / pitch).toInt().coerceAtLeast(3)
        val rows = ((size.height - hole * 2) / pitch).toInt().coerceAtLeast(3)
        val x0 = (size.width - (cols - 1) * pitch) / 2f
        val y0 = (size.height - (rows - 1) * pitch) / 2f
        val corner = minOf(cols, rows) * 0.28f      // in holes
        val live = AudioEngine.audioIsLive() && isPlaying
        val bands = AudioEngine.METER_BANDS
        for (b in 0 until bands) {
            val target = if (live) AudioEngine.levels[b] else 0f
            smooth[b] += (target - smooth[b]) * if (target > smooth[b]) 0.5f else 0.12f
        }
        val wave = AudioEngine.wave
        for (cx in 0 until cols) {
            // column -> band (bass on the left), interpolated
            val bf = cx.toFloat() * (bands - 1) / (cols - 1).coerceAtLeast(1)
            val bi = bf.toInt().coerceIn(0, bands - 1)
            val level = smooth[bi] + (smooth[(bi + 1).coerceAtMost(bands - 1)] - smooth[bi]) * (bf - bi)
            val lit = (level * rows + 0.2f).toInt()
            val wv = if (live) wave[(cx * wave.size / cols).coerceIn(0, wave.size - 1)] else 0f
            val waveRow = ((0.5f - wv * 2.4f) * (rows - 1)).toInt().coerceIn(0, rows - 1)
            for (ry in 0 until rows) {
                // trim the corners into a rounded square
                val dx = maxOf(0f, maxOf(corner - cx, cx - (cols - 1 - corner)))
                val dy = maxOf(0f, maxOf(corner - ry, ry - (rows - 1 - corner)))
                if (dx * dx + dy * dy > corner * corner + 0.3f) continue
                val on = when (mode) {
                    VisMode.Spectrum -> live && rows - 1 - ry < lit
                    VisMode.Oscilloscope -> live && ry == waveRow
                    VisMode.Off -> false
                }
                val p = Offset(x0 + cx * pitch, y0 + ry * pitch)
                if (on) {
                    drawCircle(K.orange.copy(alpha = 0.25f), hole * 1.7f, p)
                    drawCircle(K.orange, hole * 1.1f, p)
                } else {
                    drawCircle(Color.White.copy(alpha = if (K.skin.dark) 0.05f else 0.55f), hole, p + Offset(0f, 0.7f))
                    drawCircle(K.hole, hole, p)
                }
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
        // Width of the text in segment units, so the digits always fit the LCD.
        val units = text.sumOf { ch ->
            when {
                ch.isDigit() -> 24.0
                ch == ':' -> 9.0
                ch == '-' -> 14.0
                else -> 12.0
            }
        }.toFloat() - 4f
        val u = minOf(size.height / 31f, size.width / units.coerceAtLeast(1f))
        var x = (size.width - units * u).coerceAtLeast(0f)
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
    val numStyle = TextStyle(fontFamily = K.mono, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = K.scaleInk)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(70.dp)
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
        drawRect(Brush.radialGradient(listOf(Color(0x40F08C3C), Color.Transparent), center = center, radius = size.width * 0.6f))
        val x0 = size.width * 0.05f
        val x1 = size.width * 0.95f
        val totalSec = (durationMs / 1000L).coerceAtLeast(1L)
        val minutes = totalSec / 60f
        val cap = numStyle.copy(fontFamily = K.sans, fontSize = 8.5.sp, color = K.scaleInk2)

        // ---- top scale: minutes, with 30 s and 10 s (or 1 min for long tracks) marks
        val minorStep = if (totalSec > 1200) 60L else 10L
        var s = 0L
        while (s <= totalSec) {
            val x = x0 + (x1 - x0) * s / totalSec
            val major = s % 60L == 0L
            val half = !major && s % 30L == 0L
            val top = when { major -> 13; half -> 16; else -> 19 }.dp.toPx()
            drawLine(
                if (major) K.scaleInk else K.scaleInk2,
                Offset(x, top),
                Offset(x, 27.dp.toPx()),
                (if (major) 1.3 else if (half) 1.0 else 0.7).dp.toPx()
            )
            if (major && (minutes <= 12f || (s / 60L) % 5L == 0L)) {
                val lay = measurer.measure((s / 60L).toString(), numStyle)
                drawText(lay, topLeft = Offset(x - lay.size.width / 2f, 1.5.dp.toPx()))
            }
            s += minorStep
        }
        // scale captions, like the band names on a radio dial
        measurer.measure("min", cap).let { drawText(it, topLeft = Offset(size.width * 0.012f, 15.dp.toPx())) }
        measurer.measure("trk", cap).let { drawText(it, topLeft = Offset(size.width * 0.012f, 43.dp.toPx())) }

        // ---- centre rail
        val baseY = 37.dp.toPx()
        drawLine(K.scaleInk2, Offset(x0, baseY), Offset(x1, baseY), 0.8.dp.toPx())
        drawLine(K.scaleInk2.copy(alpha = 0.5f), Offset(x0, 29.dp.toPx()), Offset(x1, 29.dp.toPx()), 0.5.dp.toPx())

        // ---- bottom scale: a numbered dial (at least 12 stations, like the design).
        // Numbers past the end of the queue are printed faintly; the current track is red.
        val queueCount = trackCount.coerceAtLeast(1)
        val n = queueCount.coerceIn(12, 40)
        val segments = n - 1
        val fine = if (n <= 12) 5 else if (n <= 24) 3 else 2
        for (k in 0..segments * fine) {
            if (k % fine == 0) continue
            val x = x0 + (x1 - x0) * k / (segments * fine)
            val past = k / fine >= queueCount - 1 && queueCount < n
            drawLine(
                if (past) K.scaleInk2.copy(alpha = 0.45f) else K.scaleInk2,
                Offset(x, baseY), Offset(x, baseY + 3.5.dp.toPx()), 0.6.dp.toPx()
            )
        }
        val labelEvery = when { n <= 14 -> 1; n <= 28 -> 2; else -> 5 }
        for (i in 0 until n) {
            val x = x0 + (x1 - x0) * i / segments
            val cur = i == trackIndex
            val inQueue = i < queueCount
            val tickColor = when { cur -> K.needle; inQueue -> K.scaleInk; else -> K.scaleInk2.copy(alpha = 0.55f) }
            drawLine(
                tickColor,
                Offset(x, baseY - 4.dp.toPx()), Offset(x, baseY + 6.dp.toPx()),
                (if (cur) 1.8 else 1.1).dp.toPx()
            )
            if (i % labelEvery == 0 || cur) {
                val lay = measurer.measure(
                    (i + 1).toString().padStart(2, '0'),
                    numStyle.copy(
                        fontSize = 9.5.sp,
                        color = when { cur -> K.needle; inQueue -> K.scaleInk; else -> K.scaleInk2.copy(alpha = 0.6f) }
                    )
                )
                drawText(lay, topLeft = Offset(x - lay.size.width / 2f, baseY + 8.dp.toPx()))
            }
        }
        // thin frame lines top and bottom, like the printed dial glass
        drawLine(K.scaleInk2.copy(alpha = 0.35f), Offset(0f, size.height - 3.dp.toPx()), Offset(size.width, size.height - 3.dp.toPx()), 0.6.dp.toPx())

        // ---- needle
        val nx = x0 + (x1 - x0) * shown.coerceIn(0f, 1f)
        drawRect(K.needle.copy(alpha = 0.22f), Offset(nx - 3.dp.toPx(), 3.dp.toPx()), Size(6.dp.toPx(), size.height - 6.dp.toPx()))
        drawRect(K.needle, Offset(nx - 1.dp.toPx(), 3.dp.toPx()), Size(2.dp.toPx(), size.height - 6.dp.toPx()))
        // inner shadow
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent), endY = 4.dp.toPx()))
    }
}
