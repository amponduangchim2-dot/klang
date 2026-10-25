package app.klang.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.FADE_CHOICES
import app.klang.OptionsUi
import app.klang.ui.components.Lamp
import app.klang.ui.components.Segmented
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile

// ------------------------------------------------------------------ skins

@Composable
fun SkinsScreen(current: String, onPick: (String) -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ScreenHeader("skins", "${Skins.all.size} finishes", onBack)
        Skins.all.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    val on = s.id == current
                    Tile(
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (on) Modifier.border(2.dp, K.orange, RoundedCornerShape(14.dp)) else Modifier),
                        onClick = { onPick(s.id) }
                    ) {
                        SkinPreview(s, Modifier.fillMaxWidth().aspectRatio(1.25f))
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.label, style = K.name, modifier = Modifier.weight(1f))
                            Lamp(on)
                        }
                        Text(s.note, style = K.label, maxLines = 2)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            "Skins change the colours of the whole app. The layout and the controls stay the same.",
            style = K.label, modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/** Miniature of the player face drawn in the skin's own colours. */
@Composable
private fun SkinPreview(s: Skin, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(10.dp))) {
        val w = size.width
        val h = size.height
        val u = w / 100f
        drawRect(s.desk)
        // device body
        drawRoundRect(s.shell, Offset(5 * u, 5 * u), Size(w - 10 * u, h - 10 * u), CornerRadius(6 * u))
        // wood cheeks
        drawRect(s.wood, Offset(5 * u, 12 * u), Size(3 * u, 30 * u))
        drawRect(s.wood, Offset(w - 8 * u, 12 * u), Size(3 * u, 30 * u))
        // grille
        val gx = 12 * u; val gy = 11 * u; val gs = 32 * u
        drawRoundRect(s.tile, Offset(gx, gy), Size(gs, gs), CornerRadius(4 * u))
        for (i in 0 until 6) {
            val yy = gy + 5 * u + i * 4.4f * u
            drawRoundRect(s.hole, Offset(gx + 5 * u, yy), Size(gs - 10 * u, 1.6f * u), CornerRadius(u))
        }
        // LCD
        val lx = gx + gs + 5 * u
        drawRoundRect(s.line, Offset(lx, gy), Size(w - lx - 12 * u, gs), CornerRadius(4 * u))
        drawRoundRect(s.lcd, Offset(lx + u, gy + u), Size(w - lx - 14 * u, gs - 2 * u), CornerRadius(3.5f * u))
        for (i in 0 until 4) drawRoundRect(s.seg, Offset(lx + 6 * u + i * 8 * u, gy + 9 * u), Size(5.5f * u, 10 * u), CornerRadius(u))
        drawRoundRect(s.lcdDim, Offset(lx + 6 * u, gy + 24 * u), Size(28 * u, 2 * u), CornerRadius(u))
        // scale
        drawRoundRect(s.glow, Offset(12 * u, 48 * u), Size(w - 24 * u, 9 * u), CornerRadius(2.5f * u))
        drawLine(s.needle, Offset(40 * u, 48 * u), Offset(40 * u, 57 * u), 1.2f * u)
        // keys
        val ky = 61 * u
        val kh = h - ky - 9 * u
        drawRoundRect(s.line, Offset(12 * u, ky), Size(w - 24 * u, kh), CornerRadius(3 * u))
        val weights = listOf(1f, 1f, 1.4f, 1f, 1f)
        var x = 13 * u
        val kw = w - 26 * u - 4 * u
        weights.forEachIndexed { i, wt ->
            val ww = kw * wt / weights.sum()
            drawRoundRect(if (i == 2) s.orange else s.keyHi, Offset(x, ky + u), Size(ww, kh - 2 * u), CornerRadius(2 * u))
            x += ww + u
        }
    }
}

// ------------------------------------------------------------------ share

@Composable
fun ShareScreen(
    preview: Bitmap?,
    formatIndex: Int,
    hasSong: Boolean,
    link: String?,
    onFormat: (Int) -> Unit,
    shareImage: () -> Unit,
    shareText: () -> Unit,
    copyLink: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("share", "now playing", onBack)
        if (!hasSong) {
            Tile(Modifier.fillMaxWidth()) { Text("Play a song first, then share it from here.", style = K.body) }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("square", "story 9:16"), formatIndex, onFormat)
            Spacer(Modifier.weight(1f))
        }
        Tile(Modifier.fillMaxWidth().weight(1f), padding = 8.dp) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    Image(
                        preview.asImageBitmap(), contentDescription = "Card preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    )
                } else Text("drawing…", style = K.label)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextKey("share card", shareImage, Modifier.weight(1.3f).height(44.dp), orange = true)
            TextKey("share text", shareText, Modifier.weight(1f).height(44.dp))
            if (link != null) TextKey("copy link", copyLink, Modifier.weight(1f).height(44.dp))
        }
    }
}

// ------------------------------------------------------------------ options

class OptionsActions(
    val back: () -> Unit,
    val setFade: (Int) -> Unit,
    val setBig: (Boolean) -> Unit,
    val setKeepOn: (Boolean) -> Unit,
    val setSkipTalk: (Boolean) -> Unit,
    val setYtOpus: (Boolean) -> Unit,
    val openSkins: () -> Unit,
    val checkUpdate: () -> Unit,
    val download: (String) -> Unit,
    val openSource: () -> Unit,
)

@Composable
fun OptionsScreen(o: OptionsUi, skipTalk: Boolean, a: OptionsActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ScreenHeader("options", "settings", a.back)

        Tile(Modifier.fillMaxWidth()) {
            Text("playback", style = K.label)
            Spacer(Modifier.height(8.dp))
            Text("fade between songs", style = K.name)
            Text("The song fades out at the end and the next one fades in.", style = K.label)
            Spacer(Modifier.height(8.dp))
            Segmented(
                FADE_CHOICES.map { if (it == 0) "off" else "${it}s" },
                FADE_CHOICES.indexOf(o.fadeSec).coerceAtLeast(0),
                { a.setFade(FADE_CHOICES[it]) }
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow("skip talk in YouTube videos", "Jumps over intros and talking with SponsorBlock.", skipTalk) { a.setSkipTalk(!skipTalk) }
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                "best YouTube sound",
                if (o.ytOpus) "Opus, about 160 kbps. Falls back to AAC by itself if a phone cannot play it."
                else "AAC 128 kbps, the most compatible stream.",
                o.ytOpus
            ) { a.setYtOpus(!o.ytOpus) }
        }

        Tile(Modifier.fillMaxWidth()) {
            Text("display", style = K.label)
            Spacer(Modifier.height(8.dp))
            SwitchRow("big controls", "Taller play keys, easier to hit in the car or on a run.", o.bigControls) { a.setBig(!o.bigControls) }
            Spacer(Modifier.height(10.dp))
            SwitchRow("keep screen on", "The screen stays on while Klang is open.", o.keepScreenOn) { a.setKeepOn(!o.keepScreenOn) }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("skin", style = K.name)
                    Text(Skins.byId(o.skin).label, style = K.label)
                }
                TextKey("change", a.openSkins, Modifier.height(36.dp).width(84.dp))
            }
        }

        Tile(Modifier.fillMaxWidth()) {
            Text("about", style = K.label)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("klang", style = K.wordmark)
                Spacer(Modifier.width(8.dp))
                Text("version ${o.versionName} (${o.versionCode})", style = K.value, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextKey(if (o.update.checking) "checking…" else "check for updates", a.checkUpdate, Modifier.weight(1f).height(40.dp))
                val url = o.update.downloadUrl
                if (url != null && o.update.message?.contains("available") == true) {
                    TextKey("download", { a.download(url) }, Modifier.weight(1f).height(40.dp), orange = true)
                }
            }
            o.update.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = K.body.copy(fontSize = 13.sp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Klang is a music player in the spirit of Winamp and Droidamp, with a face inspired by Dieter Rams' work for Braun.",
                style = K.body.copy(fontSize = 13.sp, color = K.ink2)
            )
            Spacer(Modifier.height(8.dp))
            Text("built with", style = K.label)
            Credits()
            Spacer(Modifier.height(8.dp))
            TextKey("source code on github", a.openSource, Modifier.fillMaxWidth().height(38.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Credits() {
    val items = listOf(
        "IBM Plex Sans Thai" to "SIL Open Font License 1.1",
        "Media3 / ExoPlayer" to "Apache 2.0",
        "NewPipe Extractor" to "GPL v3",
        "Lyrics" to "LRCLIB (lrclib.net)",
        "Skip segments" to "SponsorBlock (CC BY-NC-SA 4.0)",
    )
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { (n, l) ->
            Row {
                Text(n, style = K.body.copy(fontSize = 13.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(l, style = K.label.copy(fontSize = 11.sp))
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, on: Boolean, toggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = K.name)
            Text(note, style = K.label)
        }
        TextKey(if (on) "on" else "off", toggle, Modifier.height(36.dp).width(64.dp), active = on)
    }
}
