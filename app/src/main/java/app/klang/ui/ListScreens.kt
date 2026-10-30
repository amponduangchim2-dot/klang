package app.klang.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import app.klang.ui.components.Seam
import app.klang.ui.components.Panel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.library.Song
import app.klang.ui.components.Glyph
import app.klang.ui.components.GlyphKey
import app.klang.ui.components.LcdBox
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile

enum class Screen { Player, Library, LibraryAdd, Queue, Eq, Search, Hub, Songs, Lyrics, Bookmarks, Skins, Share, Options, Video }

@Composable
fun ScreenHeader(title: String, model: String, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphKey(Glyph.Back, onBack, Modifier.size(36.dp), description = "Back", iconSize = 16.dp)
        Spacer(Modifier.width(10.dp))
        Text(title, style = K.wordmark.copy(fontSize = 18.sp))
        Spacer(Modifier.width(8.dp))
        Text(model, style = K.label.copy(fontSize = 12.sp))
    }
}

/** A list of songs on the light LCD. */
@Composable
internal fun SongLcdList(
    songs: List<Song>,
    currentUri: String?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollTo: Int = -1,
    trailing: ((Int) -> String)? = null,
    onAdd: ((Int) -> Unit)? = null,
) {
    val state = rememberLazyListState()
    LaunchedEffect(scrollTo) { if (scrollTo >= 0) state.scrollToItem((scrollTo - 2).coerceAtLeast(0)) }
    LcdBox(modifier, padding = 4.dp) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { i, s -> "${s.id}-$i" }) { i, s ->
                val cur = s.uri == currentUri
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (cur) K.seg.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(i) }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (cur) "▶" else (i + 1).toString().padStart(2, '0'),
                        style = K.lcdSmall.copy(color = if (cur) K.orange else K.lcdDim),
                        modifier = Modifier.width(30.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = K.lcd(s.title).copy(color = if (cur) K.orange else K.seg, fontWeight = if (cur) FontWeight.Medium else FontWeight.Normal)
                        )
                        if (s.artist.isNotBlank()) Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = K.lcd(s.artist, K.lcdSmall))
                    }
                    Text(trailing?.invoke(i) ?: formatTime(s.durationMs), style = K.lcdSmall)
                    if (onAdd != null) {
                        Spacer(Modifier.width(6.dp))
                        TextKey("+", { onAdd(i) }, Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    songs: List<Song>,
    hasPermission: Boolean,
    currentUri: String?,
    onAskPermission: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffleAll: () -> Unit,
    onBack: () -> Unit,
    addMode: Boolean = false,
    onAdd: (List<Song>) -> Unit = {},
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("local music", if (addMode) "tap a song to add it" else "library", onBack)
        Panel(Modifier.fillMaxWidth().weight(1f)) {
        if (!hasPermission) {
            Tile(Modifier.fillMaxWidth()) {
                Text("Klang Audio needs permission to see the music on this phone.", style = K.body)
                Spacer(Modifier.height(10.dp))
                TextKey("allow access", onAskPermission, Modifier.fillMaxWidth().height(44.dp), orange = true)
            }
            return@Panel
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Seam)) {
            Tile(Modifier.weight(1f).fillMaxHeight()) {
                Text("songs on this phone", style = K.label)
                Text("${songs.size}", style = K.value.copy(fontSize = 22.sp))
            }
            Tile(Modifier.weight(1f).fillMaxHeight(), padding = 8.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (addMode) {
                    TextKey("add all", { onAdd(songs) }, Modifier.fillMaxWidth().height(36.dp), orange = true)
                    TextKey("done", onBack, Modifier.fillMaxWidth().height(36.dp))
                } else {
                    TextKey("play all", { onPlay(songs, 0) }, Modifier.fillMaxWidth().height(36.dp), orange = true)
                    TextKey("shuffle all", onShuffleAll, Modifier.fillMaxWidth().height(36.dp))
                }
            }
            }
        }
        if (songs.isEmpty()) {
            Tile(Modifier.fillMaxWidth()) { Text("No music found on this phone.", style = K.body) }
        } else {
            Tile(Modifier.fillMaxWidth().weight(1f), padding = 8.dp) {
                SongLcdList(songs, currentUri, { if (addMode) onAdd(listOf(songs[it])) else onPlay(songs, it) }, Modifier.fillMaxSize())
            }
        }
            }
}
}
