package app.klang.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
            val chips = if (s.suggestions.isNotEmpty() && s.query.isNotBlank()) s.suggestions else s.history.take(8)
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.suggestions.isNotEmpty() && s.query.isNotBlank()) "suggestions" else "history", style = K.label, modifier = Modifier.weight(1f))
                    if (s.suggestions.isEmpty() || s.query.isBlank()) Text("clear", style = K.label.copy(color = K.orange), modifier = Modifier.clickable { a.clearHistory() }.padding(4.dp))
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { c -> Chip(c, false, { focus.clearFocus(); a.setQuery(c); a.submit(c) }) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("results", "bookmarks"), tab, { tab = it })
            Spacer(Modifier.weight(1f))
            TextKey(if (skipNonMusic) "skip talk: on" else "skip talk: off", a.toggleSkip, Modifier.height(36.dp).width(112.dp), active = skipNonMusic)
            if (tab == 0 && s.results.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                TextKey("add all", a.addAll, Modifier.height(36.dp).width(76.dp))
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
                            "Type a song or an artist and tap search.\nPaste a YouTube link to add it, a playlist link to import it, or a channel link to bookmark it.",
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

@Composable
private fun ResultRow(r: YtItem, a: SearchActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { a.play(r) }
            .padding(start = 8.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = K.lcd(r.title).copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(r.uploader.takeIf { it.isNotBlank() }, if (r.durationSec > 0) formatTime(r.durationSec * 1000) else null).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = K.lcd(sub, K.lcdSmall), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        TextKey("+", { a.add(r) }, Modifier.size(36.dp))
    }
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
