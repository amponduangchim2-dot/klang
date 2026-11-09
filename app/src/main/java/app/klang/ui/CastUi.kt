package app.klang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.cast.Caster
import app.klang.cast.Tv
import app.klang.ui.components.LcdBox
import app.klang.ui.components.Panel
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile
import app.klang.ui.components.TuningScale

/**
 * The phone as the TV's remote: what is on, where it is, a scale to drag through the film,
 * and keys for −10 s, play/pause, +10 s, the TV picture and stop. Shared by the web and video screens.
 */
@Composable
fun CastRemote(c: Caster, modifier: Modifier = Modifier, live: Boolean = false) {
    val tv = c.current ?: return
    val msg = c.message
    val dur = c.durationMs
    Tile(modifier.fillMaxWidth(), padding = 8.dp) {
        LcdBox(Modifier.fillMaxWidth(), padding = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvGlyph(K.orange)
                Spacer(Modifier.width(8.dp))
                Text("on " + tv.name, style = K.lcdSmall.copy(color = K.orange), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    if (live) "live" else formatTime(c.positionMs) + (if (dur > 0) " / " + formatTime(dur) else ""),
                    style = K.lcdSmall.copy(color = Color.White)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(msg ?: c.title, style = K.lcdText.copy(color = if (msg != null) K.lcdDim else Color.White, fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!live) {
            Spacer(Modifier.height(4.dp))
            TuningScale(
                progress = if (dur > 0) c.positionMs.toFloat() / dur else 0f,
                durationMs = dur.coerceAtLeast(60_000L),
                trackIndex = 0, trackCount = 0,
                onSeek = { f -> if (dur > 0) c.seekTo((f * dur).toLong()) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!live) TextKey("−10", { c.skip(-10_000) }, Modifier.weight(1f).height(42.dp))
            TextKey(if (c.playing) "pause" else "play", { c.togglePlay() }, Modifier.weight(1.3f).height(42.dp), orange = true)
            if (!live) TextKey("+10", { c.skip(10_000) }, Modifier.weight(1f).height(42.dp))
            if (c.canChangeQuality) TextKey(c.tvQuality.let { q -> if (q == 0) "tv: max" else "tv: ${q}p" }, { c.cycleQuality() }, Modifier.weight(1.2f).height(42.dp))
            TextKey("stop", { c.stop() }, Modifier.weight(1f).height(42.dp))
        }
    }
}

/** OP-1 style list of TVs on the Wi-Fi. */
@Composable
fun CastPicker(c: Caster, hasMedia: Boolean, what: String?, onPick: (Tv) -> Unit, onClose: () -> Unit, noMedia: String = "No video found on this page yet. Start the film on the page, then tap cast again. Or mirror the whole screen.") {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        c.startScan()
        onDispose { c.stopScan() }
    }
    BackHandler { onClose() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Panel(Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable(enabled = false) {}) {
            Tile(Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvGlyph(K.ink)
                    Spacer(Modifier.width(10.dp))
                    Text("cast to tv", style = K.name, modifier = Modifier.weight(1f))
                    if (c.scanning) Text("looking…", style = K.label)
                }
                Spacer(Modifier.height(8.dp))
                if (!hasMedia) {
                    Text(noMedia, style = K.body.copy(color = K.ink2, fontSize = 13.sp))
                    Spacer(Modifier.height(8.dp))
                } else if (what != null) {
                    Text("video found: $what", style = K.label.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                }
                LcdBox(Modifier.fillMaxWidth().height(220.dp), padding = 6.dp) {
                    if (c.tvs.isEmpty()) {
                        Text(
                            if (c.scanning) "looking for TVs on this Wi-Fi…" else "No TV found. The phone and the TV need the same Wi-Fi, and the TV must be on.",
                            style = K.lcdSmall.copy(fontSize = 12.sp), modifier = Modifier.padding(8.dp)
                        )
                    }
                    LazyColumn {
                        items(c.tvs, key = { it.id }) { tv ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = hasMedia) { onPick(tv) }.padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvGlyph(Color.White)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(tv.name, style = K.lcdText.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (tv.kind == Tv.Kind.GOOGLE_CAST) "google cast" else "dlna", style = K.lcdSmall)
                                }
                                if (hasMedia) Text("cast ›", style = K.lcdSmall.copy(color = K.orange))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Android's own screen mirroring: works with every site, at the phone's picture quality
                    TextKey("mirror the screen", {
                        val ok = runCatching { ctx.startActivity(android.content.Intent("android.settings.CAST_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                        if (!ok) runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, Modifier.weight(1f).height(42.dp))
                    TextKey("close", onClose, Modifier.weight(1f).height(42.dp))
                }
            }
        }
    }
}

@Composable
fun TvGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        drawRoundRect(color, Offset(2.5f * u, 4 * u), Size(19 * u, 13 * u), androidx.compose.ui.geometry.CornerRadius(2 * u), style = Stroke(2.2f * u))
        drawLine(color, Offset(8 * u, 20.5f * u), Offset(16 * u, 20.5f * u), 2.2f * u, StrokeCap.Round)
    }
}


