package app.klang.ui

import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.EqUi
import app.klang.eq.BuiltInPresets
import app.klang.eq.EqPreset
import app.klang.playback.AudioEngine
import app.klang.ui.components.Chip
import app.klang.ui.components.EqCurve
import app.klang.ui.components.Key
import app.klang.ui.components.LabelRow
import app.klang.ui.components.LcdBox
import app.klang.ui.components.NameDialog
import app.klang.ui.components.PianoRow
import app.klang.ui.components.Segmented
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile
import app.klang.ui.components.VerticalFader

class EqActions(
    val setOn: (Boolean) -> Unit,
    val setAuto: (Boolean) -> Unit,
    val setBand: (Int, Float) -> Unit,
    val setPreamp: (Float) -> Unit,
    val apply: (EqPreset) -> Unit,
    val reset: () -> Unit,
    val toggleShade: () -> Unit,
    val save: (String) -> String?,
    val rename: (String, String) -> String?,
    val delete: (String) -> Unit,
    val importFile: () -> Unit,
    val exportFile: () -> Unit,
    val share: () -> Unit,
    val back: () -> Unit,
)

private val FREQ_LABELS = listOf("60", "170", "310", "600", "1k", "3k", "6k", "12k", "14k", "16k")

private fun fmtDb(v: Float) = (if (v > 0) "+" else "") + (if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqScreen(eq: EqUi, a: EqActions) {
    var dialog by remember { mutableStateOf<String?>(null) }   // "save" | "rename"
    val gap = 8.dp
    val mineSelected = eq.mine.firstOrNull { it.name == eq.presetName }

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        ScreenHeader("equalizer", "eq 1 · 10 band", a.back)

        // ---- curve LCD
        Tile {
            LcdBox(Modifier.fillMaxWidth(), padding = 8.dp) {
                Row {
                    Text(
                        eq.presetName?.let { "preset: ${it.lowercase()}" } ?: "custom curve",
                        style = K.lcd(eq.presetName ?: "", K.lcdSmall.copy(color = K.seg)),
                        modifier = Modifier.weight(1f), maxLines = 1
                    )
                    Text(if (eq.on) "preamp ${fmtDb(eq.preamp)} dB" else "eq off", style = K.lcdSmall)
                }
                EqCurve(eq.bands, eq.preamp, eq.on)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("60", "310", "1k", "6k", "16k").forEach { Text(it, style = K.lcdSmall.copy(fontSize = 10.sp)) }
                }
            }
        }

        // ---- mode + reset / shade
        Tile {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Segmented(
                    listOf("on", "off", "auto"),
                    selected = when { !eq.on -> 1; eq.auto -> 2; else -> 0 },
                    onSelect = {
                        when (it) {
                            0 -> { a.setOn(true); a.setAuto(false) }
                            1 -> a.setOn(false)
                            2 -> { a.setOn(true); a.setAuto(true) }
                        }
                    }
                )
                Spacer(Modifier.weight(1f))
                TextKey("reset", a.reset, Modifier.height(36.dp).width(68.dp))
                Spacer(Modifier.width(6.dp))
                TextKey(if (eq.shade) "open" else "shade", a.toggleShade, Modifier.height(36.dp).width(68.dp))
            }
            if (eq.auto) {
                Spacer(Modifier.height(6.dp))
                Text("auto: Klang remembers the curve for each track and loads it next time that track plays.", style = K.label)
            }
        }

        // ---- faders
        if (!eq.shade) {
            Tile {
                LabelRow("+12 dB", "drag · double-tap = 0") {}
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    VerticalFader(eq.preamp, a.setPreamp, "pre", Modifier.weight(1.3f), accent = true)
                    FREQ_LABELS.forEachIndexed { i, f ->
                        VerticalFader(eq.bands[i], { a.setBand(i, it) }, f, Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("−12 dB", style = K.label, modifier = Modifier.weight(1f))
                    Text(
                        AudioEngine.FREQS.size.toString() + " bands + preamp",
                        style = K.label
                    )
                }
            }
        }

        // ---- quick presets 1–4
        Tile {
            LabelRow("quick presets", "1–4")
            Spacer(Modifier.height(6.dp))
            PianoRow(Modifier.fillMaxWidth().height(58.dp)) {
                BuiltInPresets.quick.forEachIndexed { k, name ->
                    val p = BuiltInPresets.byName(name)!!
                    val sel = eq.presetName == p.name
                    Key({ a.apply(p) }, Modifier.weight(1f).fillMaxWidth().height(58.dp), description = "Preset ${k + 1} ${p.name}") { fg ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${k + 1}", style = K.body.copy(fontSize = 20.sp, color = fg))
                                if (sel) Text(" ●", style = K.body.copy(fontSize = 9.sp, color = K.orange))
                            }
                            Text(p.name.lowercase(), style = K.label.copy(fontSize = 10.sp), maxLines = 1)
                        }
                    }
                }
            }
        }

        // ---- all built-in presets
        Tile {
            LabelRow("all built-in presets", BuiltInPresets.all.size.toString())
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BuiltInPresets.all.forEach { p -> Chip(p.name, eq.presetName == p.name, { a.apply(p) }) }
            }
        }

        // ---- my presets
        Tile {
            LabelRow("my presets", if (eq.mine.isEmpty()) "none yet" else "${eq.mine.size} saved")
            Spacer(Modifier.height(8.dp))
            if (eq.mine.isEmpty()) {
                Text("Shape a curve, then tap save to keep it here.", style = K.label)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    eq.mine.forEach { p -> Chip(p.name, eq.presetName == p.name, { a.apply(p) }) }
                }
            }
            Spacer(Modifier.height(10.dp))
            val keyMod = Modifier.height(38.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextKey("save", { dialog = "save" }, keyMod.weight(1f))
                TextKey("rename", { if (mineSelected != null) dialog = "rename" }, keyMod.weight(1f))
                TextKey("delete", { mineSelected?.let { a.delete(it.name) } }, keyMod.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextKey("import .eqf", a.importFile, keyMod.weight(1f))
                TextKey("export", a.exportFile, keyMod.weight(1f))
                TextKey("share", a.share, keyMod.weight(1f))
            }
            if (mineSelected == null && eq.mine.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Pick one of your presets to rename or delete it.", style = K.label)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    when (dialog) {
        "save" -> NameDialog(
            title = "Save preset",
            initial = "My preset ${eq.mine.size + 1}",
            confirm = "save",
            onDismiss = { dialog = null },
            onConfirm = a.save,
        )
        "rename" -> mineSelected?.let { cur ->
            NameDialog(
                title = "Rename preset",
                initial = cur.name,
                confirm = "rename",
                onDismiss = { dialog = null },
                onConfirm = { a.rename(cur.name, it) },
            )
        }
    }
}

/** Small EQ preview for the player tile. */
@Composable
fun MiniEqBars(bands: List<Float>, on: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val bw = size.width / 10f
        val mid = size.height / 2f
        drawLine(K.orange, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(size.width, mid), 1.dp.toPx())
        bands.forEachIndexed { i, v ->
            val y = mid - (v / 12f) * (mid - 3.dp.toPx())
            val x = i * bw + bw / 2f
            val col = if (on) K.ink else K.ink3
            drawLine(col, androidx.compose.ui.geometry.Offset(x, mid), androidx.compose.ui.geometry.Offset(x, y), 1.5.dp.toPx())
            drawCircle(col, 2.5.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}
