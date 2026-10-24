package app.klang.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.PlayerUi
import app.klang.RepeatMode
import app.klang.SLEEP_CHOICES
import app.klang.ui.components.Glyph
import app.klang.ui.components.GlyphKey
import app.klang.ui.components.GrilleVisualizer
import app.klang.ui.components.Knob
import app.klang.ui.components.KnobStyle
import app.klang.ui.components.LabelRow
import app.klang.ui.components.Lamp
import app.klang.ui.components.LcdBox
import app.klang.ui.components.OutlineIcon
import app.klang.ui.components.PianoRow
import app.klang.ui.components.PushButton
import app.klang.ui.components.SevenSegment
import app.klang.ui.components.Tile
import app.klang.ui.components.TuningScale
import app.klang.ui.components.VisMode
import app.klang.ui.components.WoodCheek

fun formatTime(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun lcdTime(ms: Long, negative: Boolean): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    val m = (s / 60) % 100
    val t = "${m.toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    return if (negative) "-$t" else t
}

/** Actions the player screen can ask for. */
class PlayerActions(
    val togglePlay: () -> Unit,
    val pause: () -> Unit,
    val stop: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seek: (Float) -> Unit,
    val setVolume: (Float) -> Unit,
    val setBalance: (Float) -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val toggleEq: () -> Unit,
    val cycleSleep: () -> Unit,
    val open: (Screen) -> Unit,
    val notYet: (String) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(ui: PlayerUi, sleepChoice: Int, sleepLeftMs: Long, libraryCount: Int, eqBands: List<Float>, eqPreset: String?, a: PlayerActions) {
    var vis by rememberSaveable { mutableStateOf(VisMode.Spectrum) }
    var remaining by rememberSaveable { mutableStateOf(false) }
    val gap = 8.dp

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        // ---- header
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("klang", style = K.wordmark)
            Spacer(Modifier.width(8.dp))
            Text("player 1", style = K.label.copy(fontSize = 12.sp))
            Spacer(Modifier.weight(1f))
            Text(if (ui.isPlaying) "playing" else if (ui.hasMedia) "ready" else "no music loaded", style = K.label.copy(fontSize = 12.sp))
            Spacer(Modifier.width(6.dp))
            Lamp(ui.isPlaying)
        }

        // ---- device: grille + LCD, with wood cheeks
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(14.dp))
                .background(K.shell)
                .border(1.dp, K.line, RoundedCornerShape(14.dp))
        ) {
            WoodCheek()
            Row(
                Modifier.weight(1f).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GrilleVisualizer(
                    mode = vis,
                    playing = ui.isPlaying,
                    onTap = { vis = VisMode.entries[(vis.ordinal + 1) % VisMode.entries.size] },
                    modifier = Modifier.size(112.dp)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LcdBox(Modifier.fillMaxWidth().clickable { remaining = !remaining }) {
                        Text(
                            if (ui.isPlaying) "▶ play" else if (ui.positionMs > 0) "❚❚ pause" else "■ stop",
                            style = K.lcdSmall.copy(color = K.seg), maxLines = 1, softWrap = false
                        )
                        Text(
                            listOfNotNull(
                                ui.kbps?.let { "${it}kbps" },
                                ui.khz?.let { "${it}kHz" },
                                when (ui.stereo) { true -> "stereo"; false -> "mono"; null -> null },
                            ).joinToString("  ").ifEmpty { " " },
                            style = K.lcdSmall.copy(fontSize = 10.sp), maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                        Spacer(Modifier.height(4.dp))
                        val shownMs = if (remaining) (ui.durationMs - ui.positionMs).coerceAtLeast(0L) else ui.positionMs
                        SevenSegment(lcdTime(shownMs, remaining), height = 34.dp)
                        Spacer(Modifier.height(4.dp))
                        val title = when {
                            !ui.hasMedia -> "open music from the library"
                            ui.artist.isNotBlank() -> "${ui.artist} – ${ui.title}"
                            else -> ui.title
                        }
                        Text(
                            "$title   ",
                            style = K.lcd(title),
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }
                    Row {
                        Text("vis: ${vis.label}", style = K.label, modifier = Modifier.weight(1f))
                        Text(
                            if (ui.count > 0) "${(ui.index + 1).toString().padStart(2, '0')}/${ui.count.toString().padStart(2, '0')}" else "--/--",
                            style = K.value
                        )
                    }
                }
            }
            WoodCheek()
        }

        // ---- position: tuning scale
        Tile {
            LabelRow("position", "${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}")
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .background(K.line, RoundedCornerShape(12.dp))
                    .padding(1.dp)
                    .background(K.shell, RoundedCornerShape(11.dp))
                    .padding(3.dp)
            ) {
                TuningScale(
                    progress = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f,
                    durationMs = ui.durationMs.coerceAtLeast(60_000L),
                    trackIndex = ui.index,
                    trackCount = ui.count.coerceAtLeast(1),
                    onSeek = a.seek,
                )
            }
        }

        // ---- transport: piano keys
        Tile {
            PianoRow(Modifier.fillMaxWidth().height(54.dp)) {
                GlyphKey(Glyph.Prev, a.previous, Modifier.weight(1f).fillMaxHeight(), description = "Previous")
                GlyphKey(Glyph.Stop, a.stop, Modifier.weight(1f).fillMaxHeight(), description = "Stop")
                GlyphKey(
                    if (ui.isPlaying) Glyph.Pause else Glyph.Play, a.togglePlay,
                    Modifier.weight(1.4f).fillMaxHeight(), orange = true,
                    description = if (ui.isPlaying) "Pause" else "Play"
                )
                GlyphKey(Glyph.Next, a.next, Modifier.weight(1f).fillMaxHeight(), description = "Next")
                GlyphKey(Glyph.Pause, a.pause, Modifier.weight(1f).fillMaxHeight(), description = "Pause")
                GlyphKey(Glyph.Eject, { a.open(Screen.Library) }, Modifier.weight(1f).fillMaxHeight(), description = "Open music")
            }
        }

        // ---- volume knob | push buttons + balance
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Tile(Modifier.weight(1f).fillMaxHeight()) {
                LabelRow("volume", (ui.volume * 100).toInt().toString())
                Spacer(Modifier.height(4.dp))
                Spacer(Modifier.weight(1f))
                Knob(
                    value = ui.volume,
                    onChange = a.setVolume,
                    modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 128.dp).fillMaxWidth(),
                    description = "Volume ${(ui.volume * 100).toInt()} percent",
                )
                Spacer(Modifier.weight(1f))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                Tile(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PushButton(ui.shuffle, a.toggleShuffle, "shuffle")
                        PushButton(ui.repeat != RepeatMode.OFF, a.cycleRepeat, if (ui.repeat == RepeatMode.ONE) "repeat 1" else "repeat")
                        PushButton(ui.eqOn, a.toggleEq, "eq")
                    }
                }
                Tile(Modifier.fillMaxWidth().weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("balance", style = K.label)
                            val b = (ui.balance * 100).toInt()
                            Text(if (b == 0) "center" else if (b < 0) "left ${-b}" else "right $b", style = K.value)
                        }
                        Knob(
                            value = (ui.balance + 1f) / 2f,
                            onChange = { a.setBalance(it * 2f - 1f) },
                            modifier = Modifier.size(64.dp),
                            style = KnobStyle.Sage,
                            ticks = 10,
                            description = "Balance",
                        )
                    }
                }
            }
        }

        // ---- windows: equalizer + playlist
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Tile(Modifier.weight(1f).fillMaxHeight(), onClick = { a.open(Screen.Eq) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("equalizer", style = K.label, modifier = Modifier.weight(1f)); Lamp(ui.eqOn)
                }
                Spacer(Modifier.height(8.dp))
                MiniEqBars(eqBands, ui.eqOn)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (!ui.eqOn) "off" else (eqPreset ?: "custom"),
                    style = K.lcd(eqPreset ?: "", K.label), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Tile(Modifier.weight(1f).fillMaxHeight(), onClick = { a.open(Screen.Queue) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("playlist", style = K.label, modifier = Modifier.weight(1f)); Lamp(ui.count > 0)
                }
                Spacer(Modifier.height(10.dp))
                Text("${ui.count}", style = K.value.copy(fontSize = 20.sp))
                Text("tracks in queue", style = K.label)
            }
        }

        // ---- tools
        val sleepLabel = if (sleepChoice == 0) "off" else if (sleepLeftMs > 0) formatTime(sleepLeftMs) + " left" else SLEEP_CHOICES[sleepChoice]
        val tools = listOf(
            ToolSpec("library", Glyph.Library, "$libraryCount songs") { a.open(Screen.Library) },
            ToolSpec("search", Glyph.Search, null) { a.notYet("YouTube search") },
            ToolSpec("lyrics", Glyph.Lyrics, null) { a.notYet("Lyrics") },
            ToolSpec("skins", Glyph.Skins, null) { a.notYet("Skins") },
            ToolSpec("sleep", Glyph.Sleep, sleepLabel, active = sleepChoice != 0) { a.cycleSleep() },
            ToolSpec("cast", Glyph.Cast, "off") { a.notYet("Cast") },
            ToolSpec("share", Glyph.Share, null) { a.notYet("Share") },
            ToolSpec("options", Glyph.Options, null) { a.notYet("Options") },
        )
        tools.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { t -> ToolTile(t, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

class ToolSpec(val name: String, val glyph: Glyph, val sub: String?, val active: Boolean = false, val onClick: () -> Unit)

@Composable
private fun ToolTile(t: ToolSpec, modifier: Modifier) {
    Tile(modifier.heightIn(min = 86.dp), onClick = t.onClick) {
        OutlineIcon(t.glyph, color = if (t.active) K.orange else K.ink)
        Spacer(Modifier.weight(1f))
        Text(t.name, style = K.name, maxLines = 1)
        if (t.sub != null) Text(t.sub, style = K.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
