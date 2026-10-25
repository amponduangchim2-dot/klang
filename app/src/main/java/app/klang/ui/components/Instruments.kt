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
    val smooth = remember { FloatArray(AudioEngine.METER_BANDS) }
    // Animate only while there is something to show: playing, or the dots still falling back.
    LaunchedEffect(playing, mode) {
        if (mode == VisMode.Off) { frame++; return@LaunchedEffect }
        while (true) {
            withFrameNanos { frame = it }
            if (!playing && smooth.all { it < 0.01f }) { frame++; break }
        }
    }
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
                    drawCircle(Color.White.copy(alpha = if (K.skin.dark) 0.06f else 0.9f), hole, p + Offset(0.6f, 0.8f))
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
 * Slim tuning strip: a thin dark red bar with tick marks above and minute numbers below,
 * and a bright red marker where the song is. Drag or tap to seek.
 * ([trackIndex] and [trackCount] are kept for callers; the track number lives on the display.)
 */
@Composable
fun TuningScale(
    progress: Float,
    durationMs: Long,
    @Suppress("UNUSED_PARAMETER") trackIndex: Int,
    @Suppress("UNUSED_PARAMETER") trackCount: Int,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val seek by rememberUpdatedState(onSeek)
    val shown = (if (dragging) dragValue else progress).coerceIn(0f, 1f)
    val deep = Color(0xFF9E130D)
    val numStyle = TextStyle(fontFamily = K.sans, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = deep, fontFeatureSettings = "tnum")
    val pad = 7.dp
    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { contentDescription = "Position. Drag to seek." }
            .pointerInput(Unit) {
                fun frac(x: Float) = ((x - pad.toPx()) / (size.width - 2 * pad.toPx())).coerceIn(0f, 1f)
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
                detectTapGestures { seek(((it.x - pad.toPx()) / (size.width - 2 * pad.toPx())).coerceIn(0f, 1f)) }
            }
    ) {
        val x0 = pad.toPx()
        val x1 = size.width - pad.toPx()
        val totalSec = (durationMs / 1000L).coerceAtLeast(1L)
        val minutes = totalSec / 60f
        val barTop = 12.dp.toPx()
        val barH = 3.dp.toPx()
        val tickBase = barTop - 2.dp.toPx()
        val nx = x0 + (x1 - x0) * shown

        // ticks: 10 s, 30 s and full minutes (1 min steps for very long tracks)
        val minorStep = if (totalSec > 1200) 60L else 10L
        var s = 0L
        while (s <= totalSec) {
            val x = x0 + (x1 - x0) * s / totalSec
            val major = s % 60L == 0L
            val half = !major && s % 30L == 0L
            val len = when { major -> 8; half -> 5; else -> 3 }.dp.toPx()
            drawLine(
                deep.copy(alpha = if (x <= nx) 1f else 0.45f),
                Offset(x, tickBase - len), Offset(x, tickBase),
                (if (major) 1.2 else 0.8).dp.toPx()
            )
            if (major) {
                val every = when { minutes <= 8f -> 1L; minutes <= 20f -> 2L; else -> 5L }
                if ((s / 60L) % every == 0L) {
                    val lay = measurer.measure((s / 60L).toString(), numStyle.copy(color = deep.copy(alpha = if (x <= nx) 1f else 0.6f)))
                    drawText(lay, topLeft = Offset((x - lay.size.width / 2f).coerceIn(0f, size.width - lay.size.width), barTop + barH + 3.dp.toPx()))
                }
            }
            s += minorStep
        }
        // the bar: dark red track, filled up to the position
        val cr = CornerRadius(barH / 2f)
        drawRoundRect(deep.copy(alpha = 0.16f), Offset(x0, barTop), Size(x1 - x0, barH), cr)
        drawRoundRect(deep, Offset(x0, barTop), Size((nx - x0).coerceAtLeast(barH), barH), cr)
        // marker
        drawCircle(Color.Black.copy(alpha = 0.18f), 4.5.dp.toPx(), Offset(nx + 0.8.dp.toPx(), barTop + barH / 2f + 1.dp.toPx()))
        drawCircle(K.needle, 4.5.dp.toPx(), Offset(nx, barTop + barH / 2f))
        drawCircle(Color.White.copy(alpha = 0.35f), 1.6.dp.toPx(), Offset(nx - 1.2.dp.toPx(), barTop + barH / 2f - 1.2.dp.toPx()))
    }
}
