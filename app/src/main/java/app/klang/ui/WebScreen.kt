package app.klang.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.klang.web.WebHolder
import app.klang.web.WebPage
import app.klang.ui.components.Glyph
import app.klang.ui.components.GlyphKey
import app.klang.ui.components.LcdBox
import app.klang.ui.components.OpKey
import app.klang.ui.components.Panel
import app.klang.ui.components.PanelRow
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile

/**
 * Web screen: a browser on the OP-1 face. Address on the OLED display, the page in a tile,
 * round keys for back / forward / reload / home, and a status strip that counts blocked ads.
 * A page's own full-screen button turns the phone sideways and fills the screen.
 */
@Composable
fun WebScreen(w: WebHolder, onFullscreen: (Boolean) -> Unit, onBack: () -> Unit, pip: Boolean = false, setPipWanted: (Boolean) -> Unit = {}) {
    val activity = LocalContext.current as? Activity
    val focus = LocalFocusManager.current
    val full = w.fullView

    DisposableEffect(Unit) {
        w.resume()
        onDispose { w.pause(); setPipWanted(false) }
    }
    // a film on the page: leaving the app floats it in a small window
    LaunchedEffect(w.media.isNotEmpty(), full != null) { setPipWanted(w.media.isNotEmpty() || full != null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> w.pause(force = true)
                androidx.lifecycle.Lifecycle.Event.ON_START -> w.resume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // floating window: the film alone, not the page around it
    LaunchedEffect(pip) { if (full == null) w.focusFilm(pip) }
    if (pip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (full != null) AndroidView(factory = { full.also { v -> (v.parent as? ViewGroup)?.removeView(v) } }, modifier = Modifier.fillMaxSize())
            else AndroidView(factory = { w.view.also { v -> (v.parent as? ViewGroup)?.removeView(v) } }, modifier = Modifier.fillMaxSize())
        }
        return
    }

    // full screen for a page's video
    LaunchedEffect(full != null) {
        val act = activity ?: return@LaunchedEffect
        val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
        onFullscreen(full != null)
        if (full != null) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (w.fullView != null) w.exitFullscreen()
            onFullscreen(false)
            activity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler {
        when {
            w.fullView != null -> w.exitFullscreen()
            w.picking -> w.cancelPicking()
            w.canBack -> w.back()
            w.url.isNotEmpty() -> w.home()
            else -> onBack()
        }
    }

    var picking by remember { mutableStateOf(false) }
    val caster = if (picking || w.media.isNotEmpty() || w.hasCaster) w.useCaster() else null
    var more by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(w.url) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(w.url) { if (!editing) field = w.url }

    // the page stays attached under a full-screen video, which needs it to keep playing
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("web", "browser", onBack)
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            // ---- address on the display
            Tile(Modifier.fillMaxWidth(), padding = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LcdBox(Modifier.weight(1f), padding = 10.dp) {
                        Box {
                            if (field.isEmpty()) Text(if (w.privateMode) "private · nothing is remembered" else "site address or words to search", style = K.lcdText.copy(color = K.lcdDim))
                            BasicTextField(
                                value = field,
                                onValueChange = { field = it; editing = true },
                                singleLine = true,
                                textStyle = K.lcdText.copy(color = Color.White, fontSize = 14.sp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); editing = false; w.go(field) }),
                                cursorBrush = SolidColor(K.orange),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Box(Modifier.size(46.dp)) {
                        app.klang.ui.components.Key({ focus.clearFocus(); editing = false; w.togglePrivate(); field = w.url }, Modifier.fillMaxSize(), dark = w.privateMode,
                            description = if (w.privateMode) "Leave private mode" else "Private mode") { c -> PrivateGlyph(c) }
                    }
                    androidx.compose.foundation.layout.Box(Modifier.size(46.dp)) {
                        app.klang.ui.components.Key({ w.toggleBookmark() }, Modifier.fillMaxSize(), description = if (w.isBookmarked) "Remove bookmark" else "Bookmark") { StarGlyph(w.isBookmarked) }
                    }
                    GlyphKey(Glyph.Search, { focus.clearFocus(); editing = false; w.go(field) }, Modifier.size(46.dp), orange = true, description = "Go")
                }
            }

            // ---- the page
            Tile(Modifier.fillMaxWidth().weight(1f), padding = 4.dp) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                    androidx.compose.runtime.key(w.privateMode) {
                        AndroidView(
                            factory = { w.view.also { v -> (v.parent as? ViewGroup)?.removeView(v) } },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (w.url.isEmpty()) StartPage(w) { focus.clearFocus(); editing = false; w.go(it.url) }
                    if (w.progress < 100 && w.url.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth(w.progress / 100f).height(3.dp).background(K.orange))
                    }
                }
            }

            // ---- the TV remote while a film is cast
            caster?.let { c -> if (c.current != null) CastRemote(c) }

            // ---- status strip: casting, blocked count, stopped pop-up, per-site switch
            if (caster?.current == null) Tile(Modifier.fillMaxWidth(), padding = 6.dp) {
                val stopped = w.stopped
                val tv = caster?.current
                val castMsg = caster?.message
                if (tv != null) {
                    // the film is on the TV: the remote sits below the page (see CastRemote)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(w.blockingHere)
                        Text("${w.blockedCount} ads blocked · film on the tv", style = K.name.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                } else if (castMsg != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(castMsg, style = K.label.copy(fontSize = 12.sp), maxLines = 2, modifier = Modifier.weight(1f))
                        TextKey("✕", { caster!!.dismissMessage() }, Modifier.size(36.dp))
                    }
                } else if (w.picking) {
                    // ---- hide a banner by hand
                    val p = w.picked
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (p == null) "tap the banner to hide" else "hide this? ${p.second}",
                            style = K.name.copy(fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        if (p != null) {
                            TextKey("bigger", { w.pickBigger() }, Modifier.height(36.dp).width(64.dp))
                            TextKey("hide", { w.hidePicked() }, Modifier.height(36.dp).width(56.dp), orange = true)
                        }
                        TextKey("✕", { w.cancelPicking() }, Modifier.size(36.dp))
                    }
                } else if (stopped != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("stopped a pop-up: " + stopped.removePrefix("https://").removePrefix("http://").take(60),
                            style = K.label.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (stopped.startsWith("http")) TextKey("open", { w.openStopped() }, Modifier.height(36.dp).width(64.dp))
                        TextKey("✕", { w.dismissStopped() }, Modifier.size(36.dp))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(w.blockingHere)
                        Text(
                            when {
                                !w.adblockOn -> "ad blocking off"
                                !w.blockingHere -> "ads allowed on this site"
                                else -> "${w.blockedCount} ads blocked"
                            },
                            style = K.name.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        if (w.url.isNotEmpty() && w.adblockOn) TextKey("hide ad", { more = false; w.startPicking() }, Modifier.height(36.dp).width(72.dp))
                        if (w.url.isNotEmpty()) androidx.compose.foundation.layout.Box(Modifier.size(36.dp)) {
                            app.klang.ui.components.Key({ w.rescan(); picking = true }, Modifier.fillMaxSize(), orange = w.media.isNotEmpty(), description = "Cast to TV") { TvGlyph(it) }
                        }
                        TextKey("⋯", { more = !more }, Modifier.size(36.dp), active = more)
                    }
                }
            }
            if (more) {
                Tile(Modifier.fillMaxWidth(), padding = 6.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextKey(if (w.adblockOn) "blocker: on" else "blocker: off", { w.toggleAdblock() }, Modifier.weight(1f).height(38.dp), active = w.adblockOn)
                        if (w.url.isNotEmpty() && w.adblockOn) TextKey(if (w.blockingHere) "allow ads here" else "block ads here", { w.toggleSite() }, Modifier.weight(1f).height(38.dp))
                        if (w.url.isNotEmpty() && w.hiddenHere > 0) TextKey("show hidden (${w.hiddenHere})", { more = false; w.unhideAllHere() }, Modifier.weight(1f).height(38.dp))
                    }
                }
            }

            // ---- round keys
            PanelRow(height = 66.dp) {
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                    OpKey({ w.back() }, Modifier.fillMaxSize(), "Back", capScale = 0.72f) { Chevron(left = true, on = w.canBack) }
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                    OpKey({ w.forward() }, Modifier.fillMaxSize(), "Forward", capScale = 0.72f) { Chevron(left = false, on = w.canForward) }
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                    OpKey({ w.reload() }, Modifier.fillMaxSize(), "Reload", capScale = 0.72f) { ReloadGlyph() }
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                    OpKey({ focus.clearFocus(); editing = false; field = ""; w.home() }, Modifier.fillMaxSize(), "Home", capScale = 0.72f) { HomeGlyph() }
                }
            }
        }
    }
    if (picking) {
        val c = w.useCaster()
        CastPicker(
            c = c,
            hasMedia = w.media.isNotEmpty(),
            what = w.media.firstOrNull()?.let { m -> (if (m.mime.contains("mpegurl", true)) "stream (hls)" else m.mime.substringAfter('/')) + " · " + (android.net.Uri.parse(m.url).host ?: "") },
            onPick = { tv -> w.castMedia()?.let { c.castTo(tv, it); w.pauseMedia() }; picking = false },
            onClose = { picking = false },
        )
    }
    if (full != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { full.also { v -> (v.parent as? ViewGroup)?.removeView(v); v.keepScreenOn = true } },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    }
}

/** Bookmarks and recent sites, on the black display, before a page is open. */
@Composable
private fun StartPage(w: WebHolder, open: (WebPage) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 12.dp, vertical = 10.dp)) {
        item {
            Text("type an address, or words to search with google", style = K.lcdSmall.copy(fontSize = 12.sp))
            Spacer(Modifier.height(10.dp))
        }
        if (w.bookmarks.isNotEmpty()) {
            item { Text("bookmarks", style = K.lcdText.copy(color = K.orange)) }
            items(w.bookmarks, key = { "b" + it.url }) { p -> PageRow(p, open) { w.removeBookmark(p) } }
            item { Spacer(Modifier.height(10.dp)) }
        }
        if (w.privateMode) {
            item {
                Text("private mode", style = K.lcdText.copy(color = K.orange))
                Spacer(Modifier.height(4.dp))
                Text("No history. Logins, cookies and site data stay in this session only and are deleted when you leave private mode or close the app.", style = K.lcdSmall.copy(fontSize = 12.sp))
            }
        } else if (w.history.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("recent", style = K.lcdText.copy(color = K.orange), modifier = Modifier.weight(1f))
                    Text("clear", style = K.lcdSmall, modifier = Modifier.clickable { w.clearHistory() }.padding(6.dp))
                }
            }
            items(w.history, key = { "h" + it.url }) { p -> PageRow(p, open, null) }
        }
    }
}

@Composable
private fun PageRow(p: WebPage, open: (WebPage) -> Unit, remove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open(p) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.title, style = K.lcdText.copy(color = Color.White, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.url.removePrefix("https://").removePrefix("http://"), style = K.lcdSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (remove != null) Text("✕", style = K.lcdSmall, modifier = Modifier.clickable { remove() }.padding(8.dp))
    }
}

@Composable
private fun StatusDot(on: Boolean) {
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(14.dp).clip(CircleShape).background(K.orange.copy(alpha = 0.22f)))
        Box(Modifier.size(if (on) 9.dp else 6.dp).clip(CircleShape).background(if (on) K.orange else K.ink3))
    }
}

// ---- line glyphs for the keys

@Composable
private fun Chevron(left: Boolean, on: Boolean) {
    val c = if (on) K.ink else K.ink3
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        val p = Path().apply {
            if (left) { moveTo(15 * u, 5 * u); lineTo(8 * u, 12 * u); lineTo(15 * u, 19 * u) }
            else { moveTo(9 * u, 5 * u); lineTo(16 * u, 12 * u); lineTo(9 * u, 19 * u) }
        }
        drawPath(p, c, style = Stroke(2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun ReloadGlyph() {
    val c = K.ink
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        drawArc(c, 30f, 290f, false, Offset(5 * u, 5 * u), Size(14 * u, 14 * u), style = Stroke(2.4f * u, cap = StrokeCap.Round))
        val p = Path().apply { moveTo(19.5f * u, 4.5f * u); lineTo(19.5f * u, 10 * u); lineTo(14 * u, 10 * u) }
        drawPath(p, c, style = Stroke(2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun StarGlyph(on: Boolean) {
    val c = if (on) K.orange else K.ink
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        val p = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 9.5f * u else 4f * u
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = 12 * u + (r * Math.cos(a)).toFloat()
            val y = 12.6f * u + (r * Math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        if (on) drawPath(p, c) else drawPath(p, c, style = Stroke(2.2f * u, join = StrokeJoin.Round))
    }
}

@Composable
private fun HomeGlyph() {
    val c = K.ink
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        val p = Path().apply {
            moveTo(4 * u, 11 * u); lineTo(12 * u, 4 * u); lineTo(20 * u, 11 * u)
            moveTo(6.5f * u, 9.5f * u); lineTo(6.5f * u, 20 * u); lineTo(17.5f * u, 20 * u); lineTo(17.5f * u, 9.5f * u)
        }
        drawPath(p, c, style = Stroke(2.3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}


/** Glasses and a hat: private mode. */
@Composable
private fun PrivateGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val u = size.minDimension / 24f
        val st = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(2.5f * u, 11f * u), Offset(21.5f * u, 11f * u), 2f * u, StrokeCap.Round)
        val hat = Path().apply { moveTo(6f * u, 11f * u); lineTo(8f * u, 4.5f * u); lineTo(16f * u, 4.5f * u); lineTo(18f * u, 11f * u) }
        drawPath(hat, color, style = st)
        drawCircle(color, 3.4f * u, Offset(7.5f * u, 17f * u), style = st)
        drawCircle(color, 3.4f * u, Offset(16.5f * u, 17f * u), style = st)
        drawLine(color, Offset(10.9f * u, 17f * u), Offset(13.1f * u, 17f * u), 2f * u, StrokeCap.Round)
    }
}
