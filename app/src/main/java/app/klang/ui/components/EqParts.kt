package app.klang.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.klang.ui.K

/**
 * Vertical fader in a dark slot, white cap with a black (or orange, for the preamp) line.
 * [db] is -12..+12; drag to change, double-tap to reset to 0.
 */
@Composable
fun VerticalFader(
    db: Float,
    onChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    height: Dp = 150.dp,
) {
    val change by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "$label ${"%+.1f".format(db)} dB" }
                .pointerInput(Unit) {
                    fun dbAt(y: Float): Float {
                        val pad = 8.dp.toPx()
                        val f = ((y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        return 12f - 24f * f
                    }
                    detectVerticalDragGestures(onDragStart = { change(dbAt(it.y)) }) { pc, _ ->
                        pc.consume()
                        change(dbAt(pc.position.y))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { change(0f) }, onTap = {
                        val pad = 8.dp.toPx()
                        val f = ((it.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        change(12f - 24f * f)
                    })
                }
        ) {
            val pad = 8.dp.toPx()
            val cx = size.width / 2f
            val top = pad
            val bottom = size.height - pad
            val mid = (top + bottom) / 2f
            // ticks at +12, +6, 0, -6, -12
            for (k in 0..4) {
                val y = top + (bottom - top) * k / 4f
                val major = k % 2 == 0
                val w = if (major) size.width * 0.36f else size.width * 0.26f
                drawLine(if (major) K.ink3 else K.line, Offset(cx - w, y), Offset(cx - 5.dp.toPx(), y), 1.dp.toPx())
                drawLine(if (major) K.ink3 else K.line, Offset(cx + 5.dp.toPx(), y), Offset(cx + w, y), 1.dp.toPx())
            }
            // slot
            val sw = 4.dp.toPx()
            drawRoundRect(Color(0xFFC9C6BE), Offset(cx - sw / 2f, top - 4.dp.toPx()), Size(sw, bottom - top + 8.dp.toPx()), CornerRadius(sw / 2f))
            drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(cx - sw / 2f, top - 4.dp.toPx()), Size(sw, 2.dp.toPx()), CornerRadius(sw / 2f))
            // cap
            val y = top + (bottom - top) * ((12f - db.coerceIn(-12f, 12f)) / 24f)
            // orange fill from 0 dB to the cap
            if (kotlin.math.abs(y - mid) > 1f) {
                drawRect(K.orange.copy(alpha = 0.85f), Offset(cx - 1.dp.toPx(), minOf(y, mid)), Size(2.dp.toPx(), kotlin.math.abs(y - mid)))
            }
            val cw = minOf(24.dp.toPx(), size.width * 0.92f)
            val ch = 13.dp.toPx()
            drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(cx - cw / 2f, y - ch / 2f + 2.dp.toPx()), Size(cw, ch), CornerRadius(4.dp.toPx()))
            drawRoundRect(Color(0xFF3A3A37), Offset(cx - cw / 2f - 1f, y - ch / 2f - 1f), Size(cw + 2f, ch + 2f), CornerRadius(4.5.dp.toPx()))
            drawRoundRect(Brush.verticalGradient(listOf(Color.White, Color(0xFFEFEDE8)), startY = y - ch / 2f, endY = y + ch / 2f), Offset(cx - cw / 2f, y - ch / 2f), Size(cw, ch), CornerRadius(4.dp.toPx()))
            drawRect(if (accent) K.orange else K.ink, Offset(cx - cw / 2f + 5.dp.toPx(), y - 1.dp.toPx()), Size(cw - 10.dp.toPx(), 2.dp.toPx()))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = K.label.copy(fontSize = 10.sp), maxLines = 1)
    }
}

/** EQ curve on the light LCD: dashed +12 / 0 / -12 lines, smooth orange curve with dots. */
@Composable
fun EqCurve(bands: List<Float>, preamp: Float, on: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        for (f in listOf(0.12f, 0.5f, 0.88f)) {
            drawLine(K.seg.copy(alpha = 0.22f), Offset(0f, h * f), Offset(w, h * f), 1.dp.toPx(), pathEffect = dash)
        }
        for (i in 1 until 5) drawLine(K.seg.copy(alpha = 0.12f), Offset(w * i / 5f, 0f), Offset(w * i / 5f, h), 1.dp.toPx())
        // a faint "signal" silhouette, like a plug-in analyser
        val sig = Path().apply {
            moveTo(0f, h)
            for (i in 0..60) {
                val x = w * i / 60f
                lineTo(x, h * 0.66f + kotlin.math.sin(i * 0.7f) * h * 0.08f + kotlin.math.cos(i * 1.9f) * h * 0.05f)
            }
            lineTo(w, h); close()
        }
        drawPath(sig, Color.White.copy(alpha = 0.32f))

        val pts = bands.mapIndexed { i, v -> Offset(w * (i + 0.5f) / 10f, h / 2f - ((v + preamp * 0.5f) / 12f).coerceIn(-1.1f, 1.1f) * (h * 0.38f)) }
        val all = listOf(Offset(0f, pts.first().y)) + pts + listOf(Offset(w, pts.last().y))
        val path = Path().apply {
            moveTo(all[0].x, all[0].y)
            for (i in 0 until all.size - 1) {
                val p0 = all[maxOf(0, i - 1)]
                val p1 = all[i]
                val p2 = all[i + 1]
                val p3 = all[minOf(all.size - 1, i + 2)]
                cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                    p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                    p2.x, p2.y
                )
            }
        }
        drawPath(path, if (on) K.orange else K.lcdDim, style = Stroke(2.2.dp.toPx()))
        pts.forEach { drawCircle(K.seg, 2.4.dp.toPx(), it) }
    }
}

/** Pill-shaped segmented switch (on / off / auto). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(K.tile2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier
                    .then(if (on) Modifier.shadow(1.5.dp, RoundedCornerShape(50)) else Modifier)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) K.keyHi else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(o, style = K.body.copy(fontSize = 13.sp, color = if (on) K.ink else K.ink2, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
            }
        }
    }
}

/** Small selectable chip used for preset lists. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) K.keyOn else K.shell)
            .then(if (selected) Modifier else Modifier.border(1.dp, K.line, RoundedCornerShape(8.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Box(Modifier.padding(end = 4.dp)) { Lamp(true) }
        }
        Text(text, style = K.body.copy(fontSize = 13.sp, color = if (selected) K.keyOnInk else K.ink))
    }
}

/** Simple Rams-style dialog asking for a name. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> String?,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(K.shell)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = K.name.copy(fontSize = 16.sp))
            LcdBox(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    textStyle = K.lcd(text, K.lcdText.copy(fontSize = 15.sp)),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (error != null) Text(error!!, style = K.label.copy(color = K.needle))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextKey("cancel", onDismiss, Modifier.weight(1f).height(42.dp))
                TextKey(confirm, {
                    val e = onConfirm(text)
                    if (e == null) onDismiss() else error = e
                }, Modifier.weight(1f).height(42.dp), orange = true)
            }
        }
    }
}
