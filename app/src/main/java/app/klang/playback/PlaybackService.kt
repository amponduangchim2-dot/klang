@file:OptIn(UnstableApi::class)

package app.klang.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.klang.library.LibraryStore
import app.klang.library.LocalMusic
import app.klang.library.Song
import app.klang.playlist.M3u
import app.klang.playlist.SavedLists
import app.klang.widget.KlangWidget
import app.klang.youtube.YouTube
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Background playback: one ExoPlayer with Klang's audio processor, exposed as a media library
 * so Android Auto (and other media browsers) can show the playlist, favorites, history and more.
 */
class PlaybackService : MediaLibraryService() {

    companion object {
        /** True while the player exists in this process (a killed app never looks "playing" on the widget). */
        @Volatile var alive = false
    }

    private var session: MediaLibrarySession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private var effectSession = 0

    // ------------------------------------------------------------------ volume / fade loop

    private val volumeLoop = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            val target = AudioEngine.userVolume * fadeFactor(p)
            if (kotlin.math.abs(p.volume - target) > 0.002f) p.volume = target
            handler.postDelayed(this, 40)
        }
    }

    private fun fadeFactor(p: Player): Float {
        val fade = AudioEngine.fadeMs
        if (fade <= 0) return 1f
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur < fade * 3) return 1f
        val pos = p.currentPosition
        val fin = pos.toFloat() / (fade / 2f)
        val fout = (dur - pos).toFloat() / fade
        val f = minOf(fin, fout).coerceIn(0f, 1f)
        return f * f
    }

    private val saveLoop = object : Runnable {
        override fun run() {
            val p = session?.player
            if (p != null && p.isPlaying) saveQueue(p)
            handler.postDelayed(this, 10_000)
        }
    }

    // ------------------------------------------------------------------ setup

    override fun onCreate() {
        super.onCreate()
        alive = true
        val context = this
        AudioEngine.loadSettings(context)

        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(KlangAudioProcessor()))
            .build()
        val renderers = RenderersFactory { h, _, audioListener, _, _ ->
            arrayOf<Renderer>(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, h, audioListener, sink))
        }
        // YouTube watch links are resolved to an audio stream only when playback starts,
        // so links in the playlist never go stale.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val upstream = DefaultDataSource.Factory(context, http)
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            val uri = spec.uri.toString()
            if (YouTube.isVideoLink(uri)) {
                val r = YouTube.resolve(uri)
                spec.buildUpon()
                    .setUri(Uri.parse(r.streamUrl))
                    .setHttpRequestHeaders(spec.httpRequestHeaders + mapOf("User-Agent" to YouTube.userAgentFor(r.streamUrl)))
                    .build()
            } else if (spec.uri.scheme == "http" || spec.uri.scheme == "https") {
                // The factory's own user agent would override per-request headers, so set it here.
                spec.withAdditionalHeaders(mapOf("User-Agent" to YouTube.BROWSER_UA))
            } else spec
        }
        val player = ExoPlayer.Builder(context, renderers, DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.volume = AudioEngine.userVolume
        player.addListener(playerListener)
        openEffects(player.audioSessionId)

        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        session = MediaLibrarySession.Builder(context, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
        refreshButtons()
        handler.post(volumeLoop)
        handler.postDelayed(saveLoop, 10_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run {
            saveQueue(player)
            KlangWidget.update(this@PlaybackService, player.mediaMetadata.title?.toString().orEmpty(), player.mediaMetadata.artist?.toString().orEmpty(), false)
            closeEffects()
            player.release()
            release()
        }
        session = null
        alive = false
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ player events

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                val md = player.mediaMetadata
                KlangWidget.update(this@PlaybackService, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), player.isPlaying)
            }
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) refreshButtons()
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying)) saveQueue(player)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = openEffects(audioSessionId)

        /** YouTube Opus failed on this phone? Try the AAC stream once. Otherwise move on to the next song. */
        override fun onPlayerError(error: PlaybackException) {
            val p = session?.player ?: return
            val uri = p.currentMediaItem?.mediaId.orEmpty()
            if (YouTube.isVideoLink(uri) && YouTube.fallBackToAac(uri)) {
                val pos = p.currentPosition
                p.seekTo(p.currentMediaItemIndex, pos)
                p.prepare()
                p.play()
                handler.postDelayed({ YouTube.retryingId = null }, 3000)
                return
            }
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
        }
    }

    private fun saveQueue(p: Player) {
        if (p.mediaItemCount == 0) return
        val songs = (0 until p.mediaItemCount).map { Items.toSong(p.getMediaItemAt(it), it) }
        Items.saveQueue(this, songs, p.currentMediaItemIndex, p.currentPosition)
    }

    // ------------------------------------------------------------------ outside equalizer apps

    /** Tells equalizer apps (Wavelet, Equalizer FX, JamesDSP…) which audio session to attach to. */
    private fun openEffects(id: Int) {
        if (id == C.AUDIO_SESSION_ID_UNSET || id == effectSession) return
        closeEffects()
        effectSession = id
        AudioEngine.audioSessionId = id
        sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC))
    }

    private fun closeEffects() {
        if (effectSession == 0) return
        sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, effectSession)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName))
        effectSession = 0
    }

    // ------------------------------------------------------------------ library for Android Auto

    private object Ids {
        const val ROOT = "root"
        const val QUEUE = "queue"
        const val FAVORITES = "favorites"
        const val MOST = "most"
        const val HISTORY = "history"
        const val LOCAL = "local"
        const val LISTS = "lists"
        const val LIST = "list:"
        /** Playable items in a folder are "parent␟index␟uri", so a tap plays the whole folder from there. */
        const val SEP = "\u001F"
    }

    private fun folder(id: String, title: String, subtitle: String? = null, playlists: Boolean = false): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(if (playlists) MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(Covers.folderUri(if (id.startsWith(Ids.LIST)) "lists" else id))
                    .build()
            )
            .build()

    private fun browseItem(parent: String, index: Int, s: Song, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(parent + Ids.SEP + index + Ids.SEP + s.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist(s.artist)
                    .setSubtitle(subtitle ?: s.artist)
                    .setAlbumTitle(s.album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(Covers.songUri(s))
                    .build()
            )
            .build()

    private fun localSongs(): List<Song> = runCatching { LocalMusic.load(this) }.getOrDefault(emptyList())

    /** Songs in a folder. Runs on the I/O thread, except the queue which reads the player. */
    private fun songsOf(parent: String): List<Pair<Song, String?>> = when {
        parent == Ids.FAVORITES -> LibraryStore.get(this).favorites().map { it to null }
        parent == Ids.MOST -> LibraryStore.get(this).mostPlayed(100).map { (s, n) -> s to "${s.artist.ifBlank { "" }}  ·  played $n×".trim(' ', '·') }
        parent == Ids.HISTORY -> LibraryStore.get(this).history(100).map { it.first to null }
        parent == Ids.LOCAL -> localSongs().sortedBy { it.title.lowercase() }.take(1500).map { it to null }
        parent.startsWith(Ids.LIST) -> {
            val text = SavedLists(this).read(parent.removePrefix(Ids.LIST))
            if (text == null) emptyList() else M3u.parse(text, localSongs()).first.map { it to null }
        }
        else -> emptyList()
    }

    private fun queueSongs(): List<Song> {
        val p = session?.player ?: return emptyList()
        return (0 until p.mediaItemCount).map { Items.toSong(p.getMediaItemAt(it), it) }
    }

    private fun <T> page(list: List<T>, page: Int, size: Int): List<T> {
        if (size <= 0 || size == Int.MAX_VALUE) return list
        val from = (page.toLong() * size).coerceAtMost(list.size.toLong()).toInt()
        return list.subList(from, (from + size).coerceAtMost(list.size))
    }

    private fun searchLocal(query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        val pool = (LibraryStore.get(this).favorites() + LibraryStore.get(this).history(200).map { it.first } + localSongs()).distinctBy { it.uri }
        return pool.filter { s ->
            val hay = "${s.title} ${s.artist} ${s.album}".lowercase()
            words.all { it in hay }
        }.take(200)
    }

    // ------------------------------------------------------------------ car buttons (Android Auto now playing)

    private object Cmd {
        const val FAV = "klang.favorite"
        const val SHUFFLE = "klang.shuffle"
        const val REPEAT = "klang.repeat"
        const val EQ = "klang.eq"
        val all = listOf(FAV, SHUFFLE, REPEAT, EQ)
    }

    private val prefs by lazy { getSharedPreferences("klang", MODE_PRIVATE) }

    private fun isFavorite(uri: String?) = uri != null && uri in prefs.getStringSet("favorites", emptySet())!!

    private fun button(cmd: String, name: String, icon: Int) =
        androidx.media3.session.CommandButton.Builder()
            .setDisplayName(name)
            .setIconResId(icon)
            .setSessionCommand(androidx.media3.session.SessionCommand(cmd, Bundle.EMPTY))
            .build()

    private fun buttons(): List<androidx.media3.session.CommandButton> {
        val p = session?.player
        val fav = isFavorite(p?.currentMediaItem?.mediaId)
        val eqName = prefs.getString("eqPreset", null)?.takeIf { AudioEngine.eqEnabled } ?: "off"
        return listOf(
            button(Cmd.FAV, if (fav) "Remove from favorites" else "Add to favorites", if (fav) app.klang.R.drawable.aa_heart_on else app.klang.R.drawable.aa_heart),
            button(Cmd.SHUFFLE, if (p?.shuffleModeEnabled == true) "Shuffle on" else "Shuffle off", if (p?.shuffleModeEnabled == true) app.klang.R.drawable.aa_shuffle_on else app.klang.R.drawable.aa_shuffle),
            button(Cmd.REPEAT, when (p?.repeatMode) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat off" },
                when (p?.repeatMode) { Player.REPEAT_MODE_ALL -> app.klang.R.drawable.aa_repeat_on; Player.REPEAT_MODE_ONE -> app.klang.R.drawable.aa_repeat_one; else -> app.klang.R.drawable.aa_repeat }),
            button(Cmd.EQ, "EQ $eqName", app.klang.R.drawable.aa_eq),
        )
    }

    private fun refreshButtons() {
        session?.setCustomLayout(com.google.common.collect.ImmutableList.copyOf(buttons()))
    }

    private fun toggleFavorite() {
        val p = session?.player ?: return
        val item = p.currentMediaItem ?: return
        val song = Items.toSong(item, p.currentMediaItemIndex)
        val set = prefs.getStringSet("favorites", emptySet())!!.toMutableSet()
        val on = song.uri !in set
        if (on) set += song.uri else set -= song.uri
        prefs.edit().putStringSet("favorites", set).apply()
        io.execute { LibraryStore.get(this).setFavorite(song, on) }
    }

    /** Steps through the four quick presets (Flat, Rock, Pop, Full Bass), like the 1–4 keys. */
    private fun nextEqPreset() {
        val quick = app.klang.eq.BuiltInPresets.quick
        val cur = prefs.getString("eqPreset", null)
        val i = quick.indexOfFirst { it.equals(cur, true) }
        val preset = app.klang.eq.BuiltInPresets.byName(quick[(i + 1) % quick.size]) ?: return
        AudioEngine.eqEnabled = true
        AudioEngine.setAllGains(preset.bands.toFloatArray())
        AudioEngine.preampDb = preset.preamp
        prefs.edit().apply {
            preset.bands.forEachIndexed { k, v -> putFloat("eq$k", v) }
            putFloat("preamp", preset.preamp)
            putString("eqPreset", preset.name)
            putBoolean("eqOn", true)
        }.apply()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().apply {
                Cmd.all.forEach { add(androidx.media3.session.SessionCommand(it, Bundle.EMPTY)) }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            session.setCustomLayout(controller, com.google.common.collect.ImmutableList.copyOf(buttons()))
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand, args: Bundle,
        ): ListenableFuture<androidx.media3.session.SessionResult> {
            val p = session.player
            when (customCommand.customAction) {
                Cmd.FAV -> toggleFavorite()
                Cmd.SHUFFLE -> p.shuffleModeEnabled = !p.shuffleModeEnabled
                Cmd.REPEAT -> p.repeatMode = when (p.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                Cmd.EQ -> nextEqPreset()
                else -> return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            refreshButtons()
            return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(folder(Ids.ROOT, "Klang Audio"), LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == Ids.ROOT) {
                val root = listOf(
                    folder(Ids.QUEUE, "Playlist", "what is playing now"),
                    folder(Ids.FAVORITES, "Favorites"),
                    folder(Ids.MOST, "Most played"),
                    folder(Ids.HISTORY, "Recently played"),
                    folder(Ids.LISTS, "Saved lists", playlists = true),
                    folder(Ids.LOCAL, "All songs", "music on this phone"),
                )
                return Futures.immediateFuture(LibraryResult.ofItemList(root, params))
            }
            if (parentId == Ids.QUEUE) {
                val items = queueSongs().mapIndexed { i, s -> browseItem(Ids.QUEUE, i, s) }
                return Futures.immediateFuture(LibraryResult.ofItemList(page(items, page, pageSize), params))
            }
            return io.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val items = if (parentId == Ids.LISTS) {
                    SavedLists(this@PlaybackService).names().map { folder(Ids.LIST + it, it, playlists = true) }
                } else {
                    songsOf(parentId).mapIndexed { i, (s, sub) -> browseItem(parentId, i, s, sub) }
                }
                LibraryResult.ofItemList(page(items, page, pageSize), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val parts = mediaId.split(Ids.SEP)
            if (parts.size == 3) {
                val s = Song(-1, parts[2], parts[2].substringAfterLast('/'), "", "", 0, 0)
                return Futures.immediateFuture(LibraryResult.ofItem(Items.fromSong(s), null))
            }
            return Futures.immediateFuture(LibraryResult.ofItem(folder(mediaId, mediaId), null))
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            io.execute {
                val n = searchLocal(query).size
                handler.post { this@PlaybackService.session?.notifySearchResultChanged(browser, query, n, params) }
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit<LibraryResult<ImmutableList<MediaItem>>> {
            val items = searchLocal(query).mapIndexed { i, s -> browseItem("search:$query", i, s) }
            LibraryResult.ofItemList(page(items, page, pageSize), params)
        }

        /** Items coming from Klang's own screen carry their URI as the media id. */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val parts = item.mediaId.split(Ids.SEP)
                when {
                    parts.size == 3 -> item.buildUpon().setMediaId(parts[2]).setUri(parts[2]).build()
                    item.localConfiguration != null -> item
                    else -> item.buildUpon().setUri(item.mediaId).build()
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** A tap in the car: play the whole folder starting at that song. A voice request: search, or resume. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val single = mediaItems.singleOrNull()
            val parts = single?.mediaId?.split(Ids.SEP)
            if (single != null && parts != null && parts.size == 3) {
                val parent = parts[0]
                val index = parts[1].toIntOrNull() ?: 0
                if (parent == Ids.QUEUE) {
                    val list = queueSongs()
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(list.map { Items.fromSong(it) }, index.coerceIn(0, (list.size - 1).coerceAtLeast(0)), 0L))
                }
                return io.submit<MediaSession.MediaItemsWithStartPosition> {
                    val songs = if (parent.startsWith("search:")) searchLocal(parent.removePrefix("search:")) else songsOf(parent).map { it.first }
                    val i = songs.indexOfFirst { it.uri == parts[2] }.takeIf { it >= 0 } ?: index
                    if (songs.isEmpty()) MediaSession.MediaItemsWithStartPosition(listOf(Items.fromSong(Song(-1, parts[2], single.mediaMetadata.title?.toString() ?: "", "", "", 0, 0))), 0, 0L)
                    else MediaSession.MediaItemsWithStartPosition(songs.map { Items.fromSong(it) }, i.coerceIn(0, songs.size - 1), 0L)
                }
            }
            val query = single?.requestMetadata?.searchQuery
            if (single != null && single.mediaId.isEmpty() && query != null) {
                return io.submit<MediaSession.MediaItemsWithStartPosition> {
                    val found = searchLocal(query)
                    if (found.isNotEmpty()) MediaSession.MediaItemsWithStartPosition(found.map { Items.fromSong(it) }, 0, 0L)
                    else resumeItems()
                }
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        /** Headphone/widget play button after the app was closed: pick up where the last queue stopped. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = io.submit<MediaSession.MediaItemsWithStartPosition> { resumeItems() }
    }

    private fun resumeItems(): MediaSession.MediaItemsWithStartPosition {
        val saved = Items.loadQueue(this)
        if (saved != null) return MediaSession.MediaItemsWithStartPosition(saved.songs.map { Items.fromSong(it) }, saved.index, saved.positionMs)
        val all = localSongs().shuffled()
        return MediaSession.MediaItemsWithStartPosition(all.map { Items.fromSong(it) }, 0, 0L)
    }
}
