package app.klang.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.klang.ui.components.Glyph
import app.klang.ui.components.GlyphIcon
import app.klang.ui.components.LcdBox
import app.klang.ui.components.OpGlyphKey
import app.klang.ui.components.OpKey
import app.klang.ui.components.Panel
import app.klang.ui.components.PanelRow
import app.klang.ui.components.Segmented
import app.klang.ui.components.TextKey
import app.klang.ui.components.Tile
import app.klang.ui.components.TuningScale
import app.klang.video.ChunkedDataSource
import app.klang.youtube.VideoPlan
import app.klang.youtube.YouTube
import app.klang.youtube.YtItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class VideoActions(
    val back: () -> Unit,
    /** Send the clip's sound to the music player and play it there. */
    val listen: (YtItem) -> Unit,
    /** Add the clip's sound to the playlist. */
    val add: (YtItem) -> Unit,
    /** Pause the music player while a video plays. */
    val pauseMusic: () -> Unit,
)

private fun qualityLabel(h: Int) = when {
    h >= 2160 -> "4k"
    h >= 1440 -> "1440"
    else -> "$h"
}

/**
 * The OP-1 style video deck: a black screen on top, the OLED display with the title,
 * the red tuning strip for the position, and round keys. Plays clips up to the highest
 * quality YouTube offers (picture and sound are separate streams, played together) and
 * live streams (adaptive HLS).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(item: YtItem, fullscreen: Boolean, setFullscreen: (Boolean) -> Unit, a: VideoActions) {
    val context = LocalContext.current
    val activity = context as? Activity

    val player = remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                playWhenReady = true
            }
    }

    var plan by remember(item.url) { mutableStateOf<VideoPlan?>(null) }
    var error by remember(item.url) { mutableStateOf<String?>(null) }
    var chosen by remember(item.url) { mutableIntStateOf(-1) }   // height; 0 = auto (live)
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var shownHeight by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }

    // ---- player events
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) a.pauseMusic()
            }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    shownHeight = minOf(videoSize.width, videoSize.height)
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                // a quality the phone can't decode: step down once, otherwise say so
                val p = plan
                if (p != null && !p.live && chosen > 0) {
                    val lower = p.qualities.firstOrNull { it.height < chosen }
                    if (lower != null) { chosen = lower.height; return }
                }
                error = "Can't play this video (" + (e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')) + ")"
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // ---- find the streams
    LaunchedEffect(item.url) {
        error = null
        plan = null
        val r = withContext(Dispatchers.IO) { runCatching { YouTube.resolveVideo(item.url) } }
        r.onSuccess { p ->
            plan = p
            chosen = when {
                p.live -> 0
                p.qualities.isEmpty() -> -2      // only the single-file fallback
                else -> (p.qualities.firstOrNull { it.height <= 1080 } ?: p.qualities.last()).height
            }
        }.onFailure { error = it.message ?: "Can't open this video" }
    }

    // ---- (re)load the source when the quality changes, keeping the position
    LaunchedEffect(plan, chosen) {
        val p = plan ?: return@LaunchedEffect
        if (chosen == -1) return@LaunchedEffect
        error = null
        if (p.live) {
            val params = player.trackSelectionParameters.buildUpon()
            if (chosen > 0) params.setMaxVideoSize(Int.MAX_VALUE, chosen) else params.clearVideoSizeConstraints()
            player.trackSelectionParameters = params.build()
            if (player.currentMediaItem == null) {
                val hls = p.hlsUrl ?: return@LaunchedEffect
                player.setMediaSource(HlsMediaSource.Factory(http(YouTube.BROWSER_UA)).createMediaSource(MediaItem.fromUri(hls)))
                player.prepare()
            }
            return@LaunchedEffect
        }
        val keep = player.currentPosition.takeIf { player.currentMediaItem != null } ?: 0L
        val q = p.qualities.firstOrNull { it.height == chosen }
        val source: MediaSource? = when {
            q != null && p.audioUrl != null -> MergingMediaSource(true, progressive(q.url), progressive(p.audioUrl))
            p.muxedUrl != null -> progressive(p.muxedUrl)
            p.hlsUrl != null -> HlsMediaSource.Factory(http(YouTube.BROWSER_UA)).createMediaSource(MediaItem.fromUri(p.hlsUrl))
            else -> null
        }
        if (source == null) { error = "No playable stream"; return@LaunchedEffect }
        player.setMediaSource(source, keep)
        player.prepare()
    }

    // ---- position ticker
    LaunchedEffect(player) {
        while (true) {
            posMs = player.currentPosition.coerceAtLeast(0)
            durMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (plan?.durationSec ?: 0L) * 1000
            delay(250)
        }
    }

    // ---- fullscreen: landscape, no system bars
    LaunchedEffect(fullscreen) {
        val act = activity ?: return@LaunchedEffect
        val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (fullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            controlsVisible = true; poke++
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                WindowCompat.getInsetsController(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // controls fade away in fullscreen while playing
    LaunchedEffect(fullscreen, controlsVisible, playing, poke) {
        if (fullscreen && controlsVisible && playing) { delay(3500); controlsVisible = false }
    }

    BackHandler { if (fullscreen) setFullscreen(false) else a.back() }

    val p = plan
    val live = p?.live == true || item.live
    val title = p?.title ?: item.title
    val channel = p?.uploader ?: item.uploader
    val heights: List<Int> = when {
        p == null -> emptyList()
        p.live -> listOf(0, 1080, 720, 480, 360)
        else -> p.qualities.map { it.height }
    }
    val status = when {
        error != null -> error!!
        p == null -> "finding streams…"
        buffering -> "loading…"
        else -> listOfNotNull(
            if (live) "● live" else null,
            if (shownHeight > 0) qualityLabel(shownHeight) + "p" else null,
            p.qualities.firstOrNull { it.height == chosen }?.codec?.substringBefore('.')?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    val togglePlay = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    val seekFrac: (Float) -> Unit = { f -> if (durMs > 0) player.seekTo((f * durMs).toLong()) }

    if (fullscreen) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, indication = null) { controlsVisible = !controlsVisible; poke++ },
            contentAlignment = Alignment.Center
        ) {
            VideoSurface(player, Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true))
            if (controlsVisible) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = K.lcdText.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (live) "● live" else "${formatTime(posMs)} / ${formatTime(durMs)}", style = K.lcdSmall.copy(color = if (live) K.orange else Color.White))
                    }
                    if (!live) TuningScale(if (durMs > 0) posMs.toFloat() / durMs else 0f, durMs, 0, 0, { seekFrac(it); poke++ })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!live) DarkKey("−10") { player.seekBack(); poke++ }
                        DarkKey(if (playing) "pause" else "play", accent = true) { togglePlay(); poke++ }
                        if (!live) DarkKey("+10") { player.seekForward(); poke++ }
                        Spacer(Modifier.weight(1f))
                        DarkKey("exit full screen") { setFullscreen(false) }
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("video", "youtube", a.back)
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            // ---- the screen
            Tile(Modifier.fillMaxWidth(), padding = 6.dp) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black)
                        .clickable { togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    VideoSurface(player, Modifier.fillMaxHeight().aspectRatio(aspect.coerceIn(0.4f, 3f), matchHeightConstraintsFirst = true))
                    if (p == null || buffering || error != null) {
                        Text(
                            if (error != null) "!" else "…",
                            style = K.lcdText.copy(color = Color.White, fontSize = 22.sp)
                        )
                    }
                }
            }

            // ---- OLED display
            Tile(Modifier.fillMaxWidth().weight(1f), padding = 6.dp) {
                LcdBox(Modifier.fillMaxSize(), padding = 10.dp) {
                    Text(title, style = K.lcd(title, K.lcdText.copy(color = Color.White, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (channel.isNotBlank()) Text(channel, style = K.lcd(channel, K.lcdSmall), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status, style = K.lcdSmall.copy(color = if (error != null || live) K.orange else K.lcdDim), maxLines = 2, modifier = Modifier.weight(1f))
                        Text(
                            if (live) "live" else "${formatTime(posMs)} / ${formatTime(durMs)}",
                            style = K.lcdText.copy(color = Color.White)
                        )
                    }
                }
            }

            // ---- position
            if (!live) {
                Tile(Modifier.fillMaxWidth(), padding = 4.dp) {
                    TuningScale(if (durMs > 0) posMs.toFloat() / durMs else 0f, durMs, 0, 0, seekFrac)
                }
            }

            // ---- keys
            PanelRow(height = 78.dp) {
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                    OpKey({ player.seekBack() }, Modifier.fillMaxSize(), "Back 10 seconds") { KeyText("−10", live) }
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                    OpGlyphKey(if (playing) Glyph.Pause else Glyph.Play, { togglePlay() }, Modifier.fillMaxSize(), if (playing) "Pause" else "Play", accent = true, iconSize = 22.dp)
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                    OpKey({ player.seekForward() }, Modifier.fillMaxSize(), "Forward 10 seconds") { KeyText("+10", live) }
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                    OpKey({ setFullscreen(true) }, Modifier.fillMaxSize(), "Full screen") { FullGlyph(K.ink) }
                }
            }

            // ---- quality
            Tile(Modifier.fillMaxWidth(), padding = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("quality", style = K.label, modifier = Modifier.padding(start = 6.dp, end = 8.dp))
                    if (heights.isEmpty()) {
                        Text(if (chosen == -2) "360 (single file)" else "…", style = K.label)
                    } else {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                            Segmented(
                                heights.map { if (it == 0) "auto" else qualityLabel(it) },
                                heights.indexOf(chosen).coerceAtLeast(0),
                                { i -> chosen = heights[i] }
                            )
                        }
                    }
                }
            }

            // ---- send the sound to the music player
            PanelRow(height = 52.dp) {
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 5.dp) {
                    TextKey("listen (sound only)", { player.pause(); a.listen(item) }, Modifier.fillMaxSize(), orange = false)
                }
                Tile(Modifier.weight(1f).fillMaxHeight(), padding = 5.dp) {
                    TextKey("+ add to playlist", { a.add(item) }, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun http(ua: String) = DefaultHttpDataSource.Factory()
    .setUserAgent(ua)
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(20_000)
    .setReadTimeoutMs(30_000)

@OptIn(UnstableApi::class)
private fun progressive(url: String): MediaSource =
    ProgressiveMediaSource.Factory(ChunkedDataSource.Factory(http(YouTube.userAgentFor(url))))
        .createMediaSource(MediaItem.fromUri(url))

@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also {
                it.keepScreenOn = true
                player.setVideoSurfaceView(it)
            }
        },
        onRelease = { player.clearVideoSurfaceView(it) },
        modifier = modifier
    )
}

@Composable
private fun KeyText(t: String, dim: Boolean) {
    Text(t, style = K.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (dim) K.ink2 else K.ink))
}

@Composable
private fun DarkKey(t: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        t,
        style = K.body.copy(fontSize = 13.sp, color = if (accent) Color.White else Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Medium),
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (accent) K.orange else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/** Four corner marks: full screen. */
@Composable
private fun FullGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val u = size.minDimension / 24f
        val w = 2.2f * u
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x * u, y * u), Offset((x + dx) * u, y * u), w, StrokeCap.Round)
            drawLine(color, Offset(x * u, y * u), Offset(x * u, (y + dy) * u), w, StrokeCap.Round)
        }
        corner(4f, 4f, 6f, 6f); corner(20f, 4f, -6f, 6f)
        corner(4f, 20f, 6f, -6f); corner(20f, 20f, -6f, -6f)
    }
}
