package app.klang.ui

import app.klang.ui.components.Seam
import app.klang.ui.components.Panel
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.LyricsUi
import app.klang.PlayerUi
import app.klang.ui.components.Glyph
import app.klang.ui.components.GlyphKey
import app.klang.ui.components.LcdBox
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile
import kotlinx.coroutines.delay

@Composable
fun LyricsScreen(
    ui: PlayerUi,
    lyrics: LyricsUi,
    currentUri: String?,
    load: (Boolean) -> Unit,
    seekTo: (Long) -> Unit,
    togglePlay: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    onBack: () -> Unit,
) {
    // fetch when the song changes
    LaunchedEffect(currentUri) { load(false) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("lyrics", "lrclib", onBack)
        Panel(Modifier.fillMaxWidth().weight(1f)) {

        Tile(Modifier.fillMaxWidth()) {
            Text(ui.title.ifBlank { "nothing playing" }, style = K.lcd(ui.title, K.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.artist.isNotBlank()) Text(ui.artist, style = K.lcd(ui.artist, K.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}",
                    style = K.value, modifier = Modifier.weight(1f)
                )
                GlyphKey(Glyph.Prev, previous, Modifier.height(36.dp).width(44.dp), description = "Previous", iconSize = 15.dp)
                GlyphKey(if (ui.isPlaying) Glyph.Pause else Glyph.Play, togglePlay, Modifier.height(36.dp).width(52.dp), orange = true, description = "Play or pause", iconSize = 15.dp)
                GlyphKey(Glyph.Next, next, Modifier.height(36.dp).width(44.dp), description = "Next", iconSize = 15.dp)
            }
        }

        Tile(Modifier.fillMaxWidth().weight(1f), padding = 8.dp) {
            LcdBox(Modifier.fillMaxSize(), padding = 4.dp) {
                when {
                    lyrics.loading || (lyrics.synced.isEmpty() && lyrics.plain == null) -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(lyrics.message ?: "Searching LRCLIB…", style = K.lcdText, textAlign = TextAlign.Center)
                            if (!lyrics.loading && currentUri != null) {
                                Spacer(Modifier.height(12.dp))
                                TextKey("try again", { load(true) }, Modifier.height(36.dp).width(100.dp))
                            }
                        }
                    }
                    lyrics.synced.isNotEmpty() -> SyncedLyrics(lyrics, ui.positionMs, seekTo)
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        val plain = lyrics.plain.orEmpty()
                        Text("plain lyrics · not synced", style = K.lcdSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(plain, style = K.lcd(plain, K.lcdText.copy(fontSize = 15.sp, lineHeight = 24.sp)))
                    }
                }
            }
        }
        lyrics.source?.let {
            Tile(Modifier.fillMaxWidth(), padding = 8.dp) {
                Text("from lrclib.net · $it", style = K.lcd(it, K.label.copy(fontSize = 11.sp)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
            }
}
}

@Composable
private fun SyncedLyrics(lyrics: LyricsUi, positionMs: Long, seekTo: (Long) -> Unit) {
    val lines = lyrics.synced
    val current = lines.indexOfLast { it.timeMs <= positionMs + 250 }
    val state = rememberLazyListState()
    // pause auto-scroll for a few seconds after the user scrolls by hand
    var touchedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(current) {
        if (current >= 0 && System.currentTimeMillis() - touchedAt > 4000) {
            state.animateScrollToItem((current - 3).coerceAtLeast(0))
        }
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) touchedAt = System.currentTimeMillis()
        delay(1)
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp)
    ) {
        itemsIndexed(lines, key = { i, l -> "$i-${l.timeMs}" }) { i, l ->
            val isCur = i == current
            val a by animateFloatAsState(if (isCur) 1f else if (i < current) 0.45f else 0.7f, label = "line")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .clickable { seekTo(l.timeMs) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    l.text.ifBlank { "♪" },
                    style = K.lcd(l.text, K.lcdText).copy(
                        fontSize = if (isCur) 18.sp else 15.sp,
                        lineHeight = if (isCur) 24.sp else 21.sp,
                        fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCur) K.orange else K.seg,
                    ),
                    modifier = Modifier.weight(1f).alpha(a)
                )
            }
        }
    }
}
