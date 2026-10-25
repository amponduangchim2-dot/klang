package app.klang.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.klang.playlist.QueueEntry
import app.klang.ui.components.Glyph
import app.klang.ui.components.GlyphKey
import app.klang.ui.components.LcdBox
import app.klang.ui.components.NameDialog
import app.klang.ui.components.PianoRow
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile
import app.klang.ui.components.Cell
import app.klang.ui.components.OpGlyphKey
import app.klang.ui.components.Panel
import app.klang.ui.components.PanelRow

class PlaylistActions(
    val back: () -> Unit,
    val tap: (Long) -> Unit,          // select, or play when already the only selection
    val longPress: (Long) -> Unit,    // add / remove from the selection
    val toggleFavorite: (String) -> Unit,
    // add
    val addUrl: (String) -> String?,
    val addFolder: () -> Unit,
    val addFiles: () -> Unit,
    val addFromLibrary: () -> Unit,
    // rem
    val removeSelected: () -> Unit,
    val crop: () -> Unit,
    val removeAll: () -> Unit,
    val removeDuplicates: () -> Unit,
    val removeUnplayable: () -> Unit,
    // sel
    val selectAll: () -> Unit,
    val selectNone: () -> Unit,
    val invert: () -> Unit,
    // misc
    val sortTitle: () -> Unit,
    val sortLength: () -> Unit,
    val reverse: () -> Unit,
    val randomize: () -> Unit,
    val rename: (Long, String) -> String?,
    val needOne: () -> Unit,
    // list
    val newList: (String) -> String?,
    val saveList: (String) -> String?,
    val loadList: (String, Boolean) -> Unit,
    val deleteList: (String) -> Unit,
    val importM3u: () -> Unit,
    val shareM3u: () -> Unit,
    val shareLinks: () -> Unit,
    // mini player
    val previous: () -> Unit,
    val togglePlay: () -> Unit,
)

private enum class Bank(val label: String) { Add("add"), Rem("rem"), Sel("sel"), Misc("misc"), List("list") }

private sealed interface PlDialog {
    data object AddUrl : PlDialog
    data object NewList : PlDialog
    data object SaveList : PlDialog
    data object LoadList : PlDialog
    data class Info(val e: QueueEntry) : PlDialog
    data class Rename(val e: QueueEntry) : PlDialog
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(
    queue: List<QueueEntry>,
    currentIndex: Int,
    selection: Set<Long>,
    favorites: Set<String>,
    listName: String,
    savedNames: List<String>,
    ui: app.klang.PlayerUi,
    a: PlaylistActions,
) {
    var bank by rememberSaveable { mutableStateOf(Bank.Add) }
    var dialog by remember { mutableStateOf<PlDialog?>(null) }
    val selected = queue.filter { it.key in selection }
    val one = selected.singleOrNull()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("current playlist", listName, a.back)

        // ---- list on the LCD
        Tile(Modifier.fillMaxWidth().weight(1f), padding = 8.dp) {
            LcdBox(Modifier.fillMaxSize(), padding = 4.dp) {
                val total = queue.sumOf { it.song.durationMs }
                val elapsed = queue.take(currentIndex.coerceAtLeast(0)).sumOf { it.song.durationMs } + ui.positionMs
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("${queue.size} tracks" + if (selection.isNotEmpty()) " · ${selection.size} selected" else "", style = K.lcdSmall, modifier = Modifier.weight(1f))
                    Text("${formatTime(elapsed)} / ${formatTime(total)}", style = K.lcdSmall.copy(color = K.seg))
                }
                if (queue.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("The playlist is empty.\nUse ADD below.", style = K.lcdText, modifier = Modifier.padding(16.dp))
                    }
                } else {
                    val state = rememberLazyListState()
                    LaunchedEffect(Unit) { state.scrollToItem((currentIndex - 2).coerceAtLeast(0)) }
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(queue, key = { _, e -> e.key }) { i, e ->
                            val cur = i == currentIndex
                            val sel = e.key in selection
                            val fav = e.song.uri in favorites
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) K.seg.copy(alpha = 0.10f) else Color.Transparent)
                                    .combinedClickable(onClick = { a.tap(e.key) }, onLongClick = { a.longPress(e.key) })
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (cur) "▶" else (i + 1).toString().padStart(2, '0'),
                                    style = K.lcdSmall.copy(color = if (cur) K.orange else K.lcdDim),
                                    modifier = Modifier.width(30.dp)
                                )
                                val name = if (e.song.artist.isNotBlank()) "${e.song.artist} – ${e.song.title}" else e.song.title
                                Text(
                                    name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = K.lcd(name).copy(color = if (cur) K.orange else K.seg, fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Medium),
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable { a.toggleFavorite(e.song.uri) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (fav) K.orange else K.ghost)
                                    )
                                }
                                Text(if (e.song.durationMs > 0) formatTime(e.song.durationMs) else "--:--", style = K.lcdSmall)
                            }
                        }
                    }
                }
            }
        }

        // ---- key banks + drawer
        Tile(Modifier.fillMaxWidth()) {
            PianoRow(Modifier.fillMaxWidth().height(42.dp)) {
                Bank.entries.forEach { b ->
                    TextKey(b.label, { bank = b }, Modifier.weight(1f).fillMaxHeight(), active = bank == b)
                }
            }
            Spacer(Modifier.height(8.dp))
            val items: List<Pair<String, () -> Unit>> = when (bank) {
                Bank.Add -> listOf(
                    "URL…" to { dialog = PlDialog.AddUrl },
                    "folder…" to a.addFolder,
                    "files…" to a.addFiles,
                    "from library" to a.addFromLibrary,
                )
                Bank.Rem -> listOf(
                    "selected" to a.removeSelected,
                    "crop (keep selected)" to a.crop,
                    "all" to a.removeAll,
                    "duplicates" to a.removeDuplicates,
                    "unplayable" to a.removeUnplayable,
                )
                Bank.Sel -> listOf("all" to a.selectAll, "none" to a.selectNone, "invert" to a.invert)
                Bank.Misc -> listOf(
                    "sort by title" to a.sortTitle,
                    "sort by length" to a.sortLength,
                    "reverse" to a.reverse,
                    "randomize" to a.randomize,
                    "file info" to { if (one != null) { dialog = PlDialog.Info(one) } else { a.needOne() } },
                    "rename" to { if (one != null) { dialog = PlDialog.Rename(one) } else { a.needOne() } },
                )
                Bank.List -> listOf(
                    "new list…" to { dialog = PlDialog.NewList },
                    "save list…" to { dialog = PlDialog.SaveList },
                    "load list…" to { dialog = PlDialog.LoadList },
                    "import .m3u" to a.importM3u,
                    "share .m3u" to a.shareM3u,
                    "share as text" to a.shareLinks,
                )
            }
            items.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (label, action) -> TextKey(label, action, Modifier.weight(1f).height(36.dp)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text(
                when (bank) {
                    Bank.Misc -> "file info and rename work on one selected song."
                    Bank.Rem, Bank.Sel -> "tap a song to select it, tap again to play. long-press to select more."
                    else -> "tap a song to select it, tap again to play."
                },
                style = K.label
            )
        }

        // ---- mini player
        Panel(Modifier.fillMaxWidth()) {
            PanelRow(height = 66.dp) {
                Cell(Modifier.width(66.dp).fillMaxHeight(), padding = 0.dp) {
                    OpGlyphKey(Glyph.Prev, a.previous, Modifier.fillMaxSize(), description = "Previous")
                }
                Cell(Modifier.weight(1f).fillMaxHeight(), padding = 6.dp) {
                    LcdBox(Modifier.fillMaxSize(), padding = 6.dp) {
                        val t = if (ui.hasMedia) (if (ui.artist.isNotBlank()) "${ui.artist} – ${ui.title}" else ui.title) else "nothing playing"
                        Text(t, style = K.lcd(t, K.lcdText.copy(fontSize = 12.sp)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}", style = K.lcdSmall)
                    }
                }
                Cell(Modifier.width(66.dp).fillMaxHeight(), padding = 0.dp) {
                    OpGlyphKey(if (ui.isPlaying) Glyph.Pause else Glyph.Play, a.togglePlay, Modifier.fillMaxSize(), description = "Play or pause", accent = true)
                }
            }
        }
    }

    // ---- dialogs
    when (val d = dialog) {
        PlDialog.AddUrl -> NameDialog("Add a link to a song or stream", "https://", "add", { dialog = null }, a.addUrl)
        PlDialog.NewList -> NameDialog("New list (clears the current one)", "My playlist", "create", { dialog = null }, a.newList)
        PlDialog.SaveList -> NameDialog("Save playlist as", listName, "save", { dialog = null }, a.saveList)
        is PlDialog.Rename -> NameDialog("Name shown in the playlist", d.e.song.title, "rename", { dialog = null }) { a.rename(d.e.key, it) }
        is PlDialog.Info -> InfoDialog(d.e) { dialog = null }
        PlDialog.LoadList -> LoadListDialog(savedNames, { dialog = null }, a.loadList, a.deleteList)
        null -> Unit
    }
}

@Composable
private fun InfoDialog(e: QueueEntry, onDismiss: () -> Unit) {
    val s = e.song
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(K.shell).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("File info", style = K.name.copy(fontSize = 16.sp))
            LcdBox(Modifier.fillMaxWidth()) {
                @Composable
                fun row(k: String, v: String) {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(k, style = K.lcdSmall, modifier = Modifier.width(64.dp))
                        Text(v.ifBlank { "—" }, style = K.lcd(v, K.lcdText.copy(fontSize = 12.sp)))
                    }
                }
                row("title", s.title)
                row("artist", s.artist)
                row("album", s.album)
                row("length", if (s.durationMs > 0) formatTime(s.durationMs) else "")
                row("size", if (s.sizeBytes > 0) "%.1f MB".format(s.sizeBytes / 1_048_576.0) else "")
                row("bitrate", s.estimatedKbps?.let { "~$it kbps" } ?: "")
                row("where", s.uri)
            }
            TextKey("close", onDismiss, Modifier.fillMaxWidth().height(42.dp))
        }
    }
}

@Composable
private fun LoadListDialog(names: List<String>, onDismiss: () -> Unit, load: (String, Boolean) -> Unit, delete: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(K.shell).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Saved playlists", style = K.name.copy(fontSize = 16.sp))
            if (names.isEmpty()) {
                Text("No saved playlists yet. Use LIST > save list.", style = K.body)
            } else {
                LcdBox(Modifier.fillMaxWidth().heightIn(max = 360.dp), padding = 4.dp) {
                    LazyColumn {
                        itemsIndexed(names) { _, n ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(n, style = K.lcd(n), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextKey("open", { load(n, false); onDismiss() }, Modifier.width(58.dp).height(32.dp))
                                Spacer(Modifier.width(4.dp))
                                TextKey("+", { load(n, true); onDismiss() }, Modifier.width(36.dp).height(32.dp))
                                Spacer(Modifier.width(4.dp))
                                TextKey("✕", { delete(n) }, Modifier.width(36.dp).height(32.dp))
                            }
                        }
                    }
                }
                Text("open replaces the playlist · + appends · ✕ deletes", style = K.label)
            }
            TextKey("close", onDismiss, Modifier.fillMaxWidth().height(42.dp))
        }
    }
}
