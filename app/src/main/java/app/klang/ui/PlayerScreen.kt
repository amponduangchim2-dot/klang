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
import app.klang.ui.components.Cell
import app.klang.ui.components.OpGlyphKey
import app.klang.ui.components.OpToggle
import app.klang.ui.components.Panel
import app.klang.ui.components.PanelRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize

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
fun PlayerScreen(ui: PlayerUi, sleepChoice: Int, sleepLeftMs: Long, libraryCount: Int, eqBands: List<Float>, eqPreset: String?, a: PlayerActions, big: Boolean = false) {
    var vis by rememberSaveable { mutableStateOf(VisMode.Spectrum) }
    var remaining by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        // One face plate on a four-column grid; every module lines up with the ones above and below.
        Panel(Modifier.fillMaxWidth()) {
            // ---- speaker (1 column) + display (3 columns)
            PanelRow {
                Cell(Modifier.weight(1f).fillMaxHeight(), padding = 6.dp) {
                    GrilleVisualizer(
                        mode = vis,
                        playing = ui.isPlaying,
                        onTap = { vis = VisMode.entries[(vis.ordinal + 1) % VisMode.entries.size] },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Cell(Modifier.weight(3f + 2f * SEAM_W), padding = 6.dp) {
                    LcdBox(Modifier.fillMaxWidth().clickable { remaining = !remaining }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (ui.isPlaying) "▶ play" else if (ui.positionMs > 0) "❚❚ pause" else "■ stop",
                                style = K.lcdSmall.copy(color = K.seg), maxLines = 1, softWrap = false,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (ui.count > 0) "${(ui.index + 1).toString().padStart(2, '0')}/${ui.count.toString().padStart(2, '0')}" else "--/--",
                                style = K.lcdSmall.copy(color = K.seg), maxLines = 1
                            )
                        }
                        Text(
                            listOfNotNull(
                                ui.kbps?.let { "${it}kbps" },
                                ui.khz?.let { "${it}kHz" },
                                when (ui.stereo) { true -> "stereo"; false -> "mono"; null -> null },
                                "vis ${vis.label}",
                            ).joinToString("  "),
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
                }
            }

            // ---- position (4 columns)
            PanelRow {
                Cell(Modifier.weight(1f)) {
                    LabelRow("position", "${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}")
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .background(K.line, RoundedCornerShape(10.dp))
                            .padding(1.dp)
                            .background(K.shell, RoundedCornerShape(9.dp))
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
            }

            // ---- transport: four OP-1 keys
            val keyH = if (big) 104.dp else 74.dp
            val ic = if (big) 26.dp else 18.dp
            PanelRow(height = keyH) {
                listOf(
                    Triple(Glyph.Prev, a.previous, "Previous"),
                    Triple(if (ui.isPlaying) Glyph.Pause else Glyph.Play, a.togglePlay, if (ui.isPlaying) "Pause" else "Play"),
                    Triple(Glyph.Stop, a.stop, "Stop"),
                    Triple(Glyph.Next, a.next, "Next"),
                ).forEachIndexed { i, (g, f, d) ->
                    Cell(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                        OpGlyphKey(g, f, Modifier.fillMaxSize(), description = d, accent = i == 1, iconSize = ic)
                    }
                }
            }

            // ---- volume | balance | shuffle · repeat · eq
            PanelRow(height = 112.dp) {
                Cell(Modifier.weight(1f).fillMaxHeight(), padding = 8.dp) {
                    LabelRow("vol", (ui.volume * 100).toInt().toString())
                    Knob(
                        value = ui.volume,
                        onChange = a.setVolume,
                        modifier = Modifier.align(Alignment.CenterHorizontally).weight(1f),
                        cap = K.orange,
                        description = "Volume ${(ui.volume * 100).toInt()} percent",
                    )
                }
                Cell(Modifier.weight(1f).fillMaxHeight(), padding = 8.dp) {
                    val b = (ui.balance * 100).toInt()
                    LabelRow("bal", if (b == 0) "C" else if (b < 0) "L${-b}" else "R$b")
                    Knob(
                        value = (ui.balance + 1f) / 2f,
                        onChange = { a.setBalance(it * 2f - 1f) },
                        modifier = Modifier.align(Alignment.CenterHorizontally).weight(1f),
                        ticks = 10,
                        cap = K.ink3,
                        description = "Balance",
                    )
                }
                Cell(Modifier.weight(2f + SEAM_W).fillMaxHeight(), padding = 8.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        OpToggle(ui.shuffle, a.toggleShuffle, "shuffle")
                        OpToggle(ui.repeat != RepeatMode.OFF, a.cycleRepeat, if (ui.repeat == RepeatMode.ONE) "repeat 1" else "repeat")
                        OpToggle(ui.eqOn, a.toggleEq, "eq")
                    }
                }
            }

            // ---- equalizer | playlist (2 + 2 columns)
            PanelRow {
                Cell(Modifier.weight(1f).fillMaxHeight(), onClick = { a.open(Screen.Eq) }) {
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
                Cell(Modifier.weight(1f).fillMaxHeight(), onClick = { a.open(Screen.Queue) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("playlist", style = K.label, modifier = Modifier.weight(1f)); Lamp(ui.count > 0)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${ui.count}", style = K.value.copy(fontSize = 22.sp))
                    Text("tracks · tap to edit", style = K.label)
                }
            }

            // ---- tools: 4 x 2 square modules
            val sleepLabel = if (sleepChoice == 0) "off" else if (sleepLeftMs > 0) formatTime(sleepLeftMs) + " left" else SLEEP_CHOICES[sleepChoice]
            val tools = listOf(
                ToolSpec("library", Glyph.Library, "$libraryCount songs") { a.open(Screen.Hub) },
                ToolSpec("search", Glyph.Search, "youtube") { a.open(Screen.Search) },
                ToolSpec("lyrics", Glyph.Lyrics, "lrclib") { a.open(Screen.Lyrics) },
                ToolSpec("skins", Glyph.Skins, Skins.byId(K.skin.id).label) { a.open(Screen.Skins) },
                ToolSpec("sleep", Glyph.Sleep, sleepLabel, active = sleepChoice != 0) { a.cycleSleep() },
                ToolSpec("output", Glyph.Cast, "speaker · bt") { a.notYet("Cast") },
                ToolSpec("share", Glyph.Share, "card") { a.open(Screen.Share) },
                ToolSpec("options", Glyph.Options, null) { a.open(Screen.Options) },
            )
            tools.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { t -> ToolTile(t, Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A module that spans n columns also covers the n-1 seams between them. Seams are 2 dp
 * against columns of roughly 80 dp, so a span gets this much extra weight per seam.
 */
private const val SEAM_W = 0.025f

class ToolSpec(val name: String, val glyph: Glyph, val sub: String?, val active: Boolean = false, val onClick: () -> Unit)

@Composable
private fun ToolTile(t: ToolSpec, modifier: Modifier) {
    Cell(modifier.aspectRatio(1f, matchHeightConstraintsFirst = false), onClick = t.onClick) {
        OutlineIcon(t.glyph, color = if (t.active) K.orange else K.ink)
        Spacer(Modifier.weight(1f))
        Text(t.name, style = K.name, maxLines = 1)
        if (t.sub != null) Text(t.sub, style = K.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
