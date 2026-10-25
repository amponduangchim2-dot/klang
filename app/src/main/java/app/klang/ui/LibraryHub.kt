package app.klang.ui

import app.klang.ui.components.Seam
import app.klang.ui.components.Panel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.library.Song
import app.klang.ui.components.Glyph
import app.klang.ui.components.LcdBox
import app.klang.ui.components.OutlineIcon
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile

/** Which list the song list screen shows. Saved lists are "list:<name>". */
object ListKind {
    const val FAVORITES = "favorites"
    const val MOST = "most"
    const val HISTORY = "history"
    fun saved(name: String) = "list:$name"
}

class HubActions(
    val back: () -> Unit,
    val openLocal: () -> Unit,
    val openList: (String) -> Unit,
    val openBookmarks: () -> Unit,
    val playSaved: (String) -> Unit,
    val appendSaved: (String) -> Unit,
    val deleteSaved: (String) -> Unit,
)

@Composable
fun LibraryHub(
    localCount: Int,
    favorites: Int,
    mostPlayedTop: Song?,
    historyCount: Int,
    lastPlayed: Song?,
    bookmarks: Int,
    savedNames: List<String>,
    a: HubActions,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ScreenHeader("library", "klang audio", a.back)
        Panel(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Seam)) {

        // big tile: local music
        Tile(Modifier.fillMaxWidth().heightIn(min = 110.dp), onClick = a.openLocal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlineIcon(Glyph.Library)
                Spacer(Modifier.width(10.dp))
                Text("local music", style = K.name, modifier = Modifier.weight(1f))
                Text("open ›", style = K.label.copy(color = K.orange))
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$localCount", style = K.value.copy(fontSize = 34.sp))
                Spacer(Modifier.width(6.dp))
                Text("songs on this phone", style = K.label, modifier = Modifier.padding(bottom = 6.dp))
            }
        }

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Seam)) {
            HubTile("favorites", "$favorites", "songs you starred", Modifier.weight(1f).fillMaxHeight()) { a.openList(ListKind.FAVORITES) }
            HubTile("most played", mostPlayedTop?.title ?: "—", if (mostPlayedTop != null) "top of the chart" else "nothing played yet",
                Modifier.weight(1f).fillMaxHeight(), small = true) { a.openList(ListKind.MOST) }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Seam)) {
            HubTile("history", "$historyCount", lastPlayed?.let { "last: ${it.title}" } ?: "recently played",
                Modifier.weight(1f).fillMaxHeight()) { a.openList(ListKind.HISTORY) }
            HubTile("bookmarks", "$bookmarks", "youtube channels & playlists", Modifier.weight(1f).fillMaxHeight()) { a.openBookmarks() }
        }

        // saved playlists
        Tile(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("saved lists", style = K.label, modifier = Modifier.weight(1f))
                Text("${savedNames.size}", style = K.value)
            }
            Spacer(Modifier.height(6.dp))
            if (savedNames.isEmpty()) {
                Text("Save the playlist from the playlist screen (list › save) and it shows up here.", style = K.body.copy(color = K.ink2, fontSize = 13.sp))
            } else {
                LcdBox(Modifier.fillMaxWidth(), padding = 4.dp) {
                    savedNames.forEach { n ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable { a.openList(ListKind.saved(n)) }
                                .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(n, style = K.lcd(n), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextKey("play", { a.playSaved(n) }, Modifier.height(32.dp).width(50.dp), orange = true)
                            TextKey("+", { a.appendSaved(n) }, Modifier.size(32.dp))
                            TextKey("✕", { a.deleteSaved(n) }, Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
        }
        }
}
}

@Composable
private fun HubTile(label: String, big: String, sub: String, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
    Tile(modifier.heightIn(min = 104.dp), onClick = onClick) {
        Text(label, style = K.label)
        Spacer(Modifier.weight(1f))
        Text(
            big, maxLines = if (small) 2 else 1, overflow = TextOverflow.Ellipsis,
            style = if (small) K.lcd(big, K.name) else K.value.copy(fontSize = 28.sp)
        )
        Text(sub, style = K.lcd(sub, K.label.copy(fontSize = 11.sp)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One of the library lists: favorites, most played, history or a saved list. */
@Composable
fun SongListScreen(
    title: String,
    model: String,
    songs: List<Song>,
    currentUri: String?,
    trailing: ((Int) -> String)?,
    empty: String,
    onPlay: (List<Song>, Int) -> Unit,
    onAdd: (List<Song>) -> Unit,
    onBack: () -> Unit,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader(title, model, onBack)
        Panel(Modifier.fillMaxWidth().weight(1f)) {
        Tile(Modifier.fillMaxWidth(), padding = 6.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextKey("play all", { if (songs.isNotEmpty()) onPlay(songs, 0) }, Modifier.weight(1f).height(38.dp), orange = true)
            TextKey("shuffle", { if (songs.isNotEmpty()) onPlay(songs.shuffled(), 0) }, Modifier.weight(1f).height(38.dp))
            TextKey("add all", { if (songs.isNotEmpty()) onAdd(songs) }, Modifier.weight(1f).height(38.dp))
            if (extraLabel != null && onExtra != null) TextKey(extraLabel, onExtra, Modifier.weight(1f).height(38.dp))
        }
        }
        if (songs.isEmpty()) {
            Tile(Modifier.fillMaxWidth()) { Text(empty, style = K.body) }
        } else {
            Tile(Modifier.fillMaxWidth().weight(1f), padding = 8.dp) {
                SongLcdList(
                    songs, currentUri, { onPlay(songs, it) }, Modifier.fillMaxSize(),
                    trailing = trailing, onAdd = { onAdd(listOf(songs[it])) }
                )
            }
        }
            }
}
}

/** "5 min ago", "3 h ago", "2 d ago". */
fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "$m min"
        m < 60 * 24 -> "${m / 60} h"
        else -> "${m / (60 * 24)} d"
    }
}
