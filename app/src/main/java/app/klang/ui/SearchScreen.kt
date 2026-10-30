package app.klang.ui

import app.klang.ui.components.Seam
import app.klang.ui.components.Panel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.klang.SearchUi
import app.klang.youtube.Bookmark
import app.klang.youtube.YouTube
import app.klang.youtube.YtItem
import app.klang.ui.components.Chip
import app.klang.ui.components.Glyph
import app.klang.ui.components.GlyphIcon
import app.klang.ui.components.GlyphKey
import app.klang.ui.components.Key
import app.klang.ui.components.LabelRow
import app.klang.ui.components.LcdBox
import app.klang.ui.components.Segmented
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile
import kotlinx.coroutines.delay

class SearchActions(
    val back: () -> Unit,
    val setQuery: (String) -> Unit,
    val suggest: (String) -> Unit,
    val submit: (String) -> Unit,
    val voice: () -> Unit,
    val play: (YtItem) -> Unit,
    /** Open the clip on the video screen. */
    val watch: (YtItem) -> Unit,
    val add: (YtItem) -> Unit,
    val addAll: () -> Unit,
    val clearHistory: () -> Unit,
    val openBookmark: (Bookmark) -> Unit,
    val deleteBookmark: (Bookmark) -> Unit,
    val addBookmark: (String) -> Unit,
    val toggleSkip: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(s: SearchUi, skipNonMusic: Boolean, a: SearchActions, startTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }   // 0 results, 1 bookmarks
    val focus = LocalFocusManager.current

    // suggestions while typing, 300 ms after the last key
    LaunchedEffect(s.query) {
        if (s.query.length >= 2 && !YouTube.isYouTube(s.query)) {
            delay(300)
            a.suggest(s.query)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("search", "youtube", a.back)
        Panel(Modifier.fillMaxWidth().weight(1f)) {

        // ---- search box
        Tile(Modifier.fillMaxWidth()) {
            LabelRow("YouTube link, or words to search", if (s.loading) "searching…" else null)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LcdBox(Modifier.weight(1f), padding = 10.dp) {
                    Box {
                        if (s.query.isEmpty()) Text("song, artist or link", style = K.lcdText.copy(color = K.lcdDim))
                        BasicTextField(
                            value = s.query,
                            onValueChange = a.setQuery,
                            singleLine = true,
                            textStyle = K.lcd(s.query, K.lcdText.copy(fontSize = 15.sp)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); a.submit(s.query) }),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(K.orange),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Key(a.voice, Modifier.size(46.dp), description = "Voice search") { fg -> MicGlyph(fg) }
                GlyphKey(Glyph.Search, { focus.clearFocus(); a.submit(s.query) }, Modifier.size(46.dp), orange = true, description = "Search")
            }
            // one slim scrolling row; gone once the search is done so the list gets the room
            val typing = s.query.isNotBlank() && s.query != s.heading
            val showSuggest = typing && s.suggestions.isNotEmpty()
            val showHistory = !showSuggest && s.query.isBlank() && s.results.isEmpty() && s.history.isNotEmpty()
            if (showSuggest || showHistory) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (showHistory) MiniChip("clear", accent = true) { a.clearHistory() }
                    (if (showSuggest) s.suggestions.take(10) else s.history.take(10)).forEach { c ->
                        MiniChip(c) { focus.clearFocus(); a.setQuery(c); a.submit(c) }
                    }
                }
            }
        }

        Tile(Modifier.fillMaxWidth(), padding = 6.dp) {
            Segmented(listOf("results", "bookmarks"), tab, { tab = it }, Modifier.fillMaxWidth(), fill = true)
            if (tab == 0) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextKey(if (skipNonMusic) "skip talk: on" else "skip talk: off", a.toggleSkip, Modifier.weight(1f).height(40.dp), active = skipNonMusic)
                    TextKey("add all to playlist", { if (s.results.isNotEmpty()) a.addAll() }, Modifier.weight(1f).height(40.dp))
                }
            }
        }

        if (tab == 0) {
            Tile(Modifier.fillMaxWidth().weight(1f), padding = 8.dp) {
                LcdBox(Modifier.fillMaxSize(), padding = 4.dp) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(s.heading?.let { it } ?: "results", style = K.lcd(s.heading ?: "", K.lcdSmall.copy(color = K.seg)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (s.results.isNotEmpty()) "${s.results.size} videos" else "", style = K.lcdSmall)
                    }
                    when {
                        s.error != null && s.results.isEmpty() -> Text(s.error, style = K.lcdText, modifier = Modifier.padding(12.dp))
                        s.results.isEmpty() && !s.loading -> Text(
                            "Type a song or an artist and tap search. Tap a clip to watch it, + to add its sound to the playlist.\nPaste a YouTube link to add it, a playlist or Mix link to import it (a Mix is also saved to the library), or a channel link to bookmark it.",
                            style = K.lcd("", K.lcdText.copy(fontSize = 12.sp)), modifier = Modifier.padding(12.dp)
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(s.results, key = { i, r -> "$i-${r.url}" }) { _, r -> ResultRow(r, a) }
                        }
                    }
                }
            }
        } else {
            Tile(Modifier.fillMaxWidth().weight(1f), padding = 8.dp) {
                Text("Paste a channel or playlist link in the box above and tap search to bookmark it.", style = K.label, modifier = Modifier.padding(4.dp))
                Spacer(Modifier.height(6.dp))
                if (s.bookmarks.isEmpty()) {
                    Text("No bookmarks yet.", style = K.body, modifier = Modifier.padding(4.dp))
                } else {
                    LcdBox(Modifier.fillMaxSize(), padding = 4.dp) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(s.bookmarks, key = { _, b -> b.url }) { _, b ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                        .clickable { a.openBookmark(b); tab = 0 }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (b.isChannel) "CH" else "PL", style = K.lcdSmall.copy(color = K.orange), modifier = Modifier.width(30.dp))
                                    Text(b.name, style = K.lcd(b.name), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TextKey("✕", { a.deleteBookmark(b) }, Modifier.size(32.dp))
                                }
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
private fun ResultRow(r: YtItem, a: SearchActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { a.watch(r) }
            .padding(start = 4.dp, top = 5.dp, bottom = 5.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // clip cover, 16:9, with its length or a red live tag
        Box(Modifier.width(112.dp).height(63.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF161616))) {
            if (r.thumbnail != null) {
                AsyncImage(
                    model = r.thumbnail, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
            val tag = if (r.live) "● live" else if (r.durationSec > 0) formatTime(r.durationSec * 1000) else null
            if (tag != null) Text(
                tag,
                style = K.lcdSmall.copy(color = Color.White, fontSize = 10.sp, lineHeight = 12.sp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (r.live) K.orange else Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = K.lcd(r.title).copy(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.uploader.isNotBlank()) Text(r.uploader, style = K.lcd(r.uploader, K.lcdSmall), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        TextKey("+", { a.add(r) }, Modifier.size(36.dp))
    }
}

/** Small one-line chip for suggestions and history. */
@Composable
private fun MiniChip(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        maxLines = 1,
        style = K.body.copy(fontSize = 12.sp, lineHeight = 14.sp, color = if (accent) K.orange else K.ink2),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(K.tile2).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun MicGlyph(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(9f * u, 3.5f * u), androidx.compose.ui.geometry.Size(6f * u, 11f * u), androidx.compose.ui.geometry.CornerRadius(3f * u))
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(color, 0f, 180f, false, androidx.compose.ui.geometry.Offset(6f * u, 5.5f * u), androidx.compose.ui.geometry.Size(12f * u, 12f * u), style = stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(12f * u, 17.5f * u), androidx.compose.ui.geometry.Offset(12f * u, 21f * u), 2f * u)
    }
}
