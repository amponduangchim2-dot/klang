package app.klang

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.net.Uri
import app.klang.eq.BuiltInPresets
import app.klang.eq.EqPreset
import app.klang.eq.Eqf
import app.klang.library.LibraryStore
import app.klang.library.LocalMusic
import app.klang.lyrics.LrcLib
import app.klang.lyrics.LyricLine
import app.klang.lyrics.LyricsUnavailable
import org.json.JSONArray
import org.json.JSONObject
import app.klang.library.Song
import app.klang.playlist.M3u
import app.klang.playlist.QueueEntry
import app.klang.playlist.SavedLists
import app.klang.playlist.audioInTree
import app.klang.playlist.isPlayable
import app.klang.playlist.keepAccess
import app.klang.playlist.songFromDocument
import app.klang.playlist.songFromUrl
import app.klang.youtube.Bookmark
import app.klang.youtube.YouTube
import app.klang.youtube.YtItem
import androidx.media3.common.PlaybackException
import app.klang.playback.AudioEngine
import app.klang.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerUi(
    val connected: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    val index: Int = 0,
    val count: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val kbps: Int? = null,
    val khz: Int? = null,
    val stereo: Boolean? = null,
    val volume: Float = 0.7f,
    val balance: Float = 0f,
    val eqOn: Boolean = true,
)

data class EqUi(
    val on: Boolean = true,
    val auto: Boolean = false,
    val preamp: Float = 0f,
    val bands: List<Float> = List(10) { 0f },
    /** Name of the preset the curve came from, or null when edited by hand. */
    val presetName: String? = "Flat",
    val mine: List<EqPreset> = emptyList(),
    val shade: Boolean = false,
)

data class SearchUi(
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** Title above the results: the query, or a bookmark's name. */
    val heading: String? = null,
    val results: List<YtItem> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
)

data class LibraryLists(
    val favorites: List<Song> = emptyList(),
    val mostPlayed: List<Pair<Song, Int>> = emptyList(),
    val history: List<Pair<Song, Long>> = emptyList(),
)

data class LyricsUi(
    val forUri: String? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val synced: List<LyricLine> = emptyList(),
    val plain: String? = null,
    val source: String? = null,
)

/** Sleep timer choices, same set as the original app. */
val SLEEP_CHOICES = listOf("off", "15 min", "30 min", "45 min", "60 min", "90 min", "end of song")
private val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60, 90, -1)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("klang", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue
    private var nextKey = 1L
    private fun entry(s: Song) = QueueEntry(nextKey++, s)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    private val savedLists = SavedLists(app)
    private val _savedNames = MutableStateFlow<List<String>>(emptyList())
    val savedNames: StateFlow<List<String>> = _savedNames

    private val _listName = MutableStateFlow("list 1")
    val listName: StateFlow<String> = _listName

    private val _sleep = MutableStateFlow(0)
    val sleepChoice: StateFlow<Int> = _sleep
    private var sleepJob: Job? = null

    private val _sleepLeftMs = MutableStateFlow(0L)
    val sleepLeftMs: StateFlow<Long> = _sleepLeftMs

    private val _eq = MutableStateFlow(EqUi())
    val eq: StateFlow<EqUi> = _eq

    /** One-off messages for the UI to show (toasts). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun messageShown() { _message.value = null }

    private val _search = MutableStateFlow(SearchUi())
    val search: StateFlow<SearchUi> = _search

    private val _skipNonMusic = MutableStateFlow(true)
    val skipNonMusic: StateFlow<Boolean> = _skipNonMusic
    private val segmentLoads = HashSet<String>()

    private val store = LibraryStore(app)
    private val _lists = MutableStateFlow(LibraryLists())
    val lists: StateFlow<LibraryLists> = _lists
    private var countedKey: Long? = null

    private val _lyrics = MutableStateFlow(LyricsUi())
    val lyrics: StateFlow<LyricsUi> = _lyrics

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onPlayerError(error: PlaybackException) {
            val why = generateSequence(error as Throwable) { it.cause }
                .firstOrNull { it is app.klang.youtube.TrackUnavailable }?.message
                ?: error.cause?.message?.take(80) ?: error.errorCodeName
            _message.value = "Cannot play that: $why"
            val c = controller ?: return
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_eq.value.auto) mediaItem?.mediaId?.let { applyAutoPresetFor(it) }
            if (_sleep.value == SLEEP_MINUTES.lastIndex && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                setSleep(0)
            }
        }
    }

    init {
        AudioEngine.balance = prefs.getFloat("balance", 0f)
        AudioEngine.eqEnabled = prefs.getBoolean("eqOn", true)
        AudioEngine.preampDb = prefs.getFloat("preamp", 0f)
        AudioEngine.setAllGains(FloatArray(AudioEngine.BAND_COUNT) { prefs.getFloat("eq$it", 0f) })
        _ui.value = _ui.value.copy(
            volume = prefs.getFloat("volume", 0.7f),
            balance = AudioEngine.balance,
            eqOn = AudioEngine.eqEnabled,
        )
        _eq.value = EqUi(
            on = AudioEngine.eqEnabled,
            auto = prefs.getBoolean("eqAuto", false),
            preamp = AudioEngine.preampDb,
            bands = List(AudioEngine.BAND_COUNT) { AudioEngine.gainDb(it) },
            presetName = prefs.getString("eqPreset", "Flat"),
            mine = loadMine(),
        )
        _favorites.value = prefs.getStringSet("favorites", emptySet())!!.toSet() + store.favoriteUris()
        refreshLists()
        _skipNonMusic.value = prefs.getBoolean("skipNonMusic", true)
        _search.value = SearchUi(history = loadHistory(), bookmarks = loadBookmarks())
        _savedNames.value = savedLists.names()
        _listName.value = prefs.getString("listName", "list 1")!!
        connect()
        viewModelScope.launch {
            var tick = 0
            while (true) {
                refresh()
                skipSegmentsIfNeeded()
                countPlayIfNeeded()
                if (++tick % 20 == 0) saveQueueState()
                delay(250)
            }
        }
    }

    private fun connect() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            c.volume = _ui.value.volume
            c.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
            c.repeatMode = when (prefs.getInt("repeat", 0)) {
                1 -> Player.REPEAT_MODE_ALL
                2 -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            restoreQueue(c)
            refresh()
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun loadLibrary() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { LocalMusic.load(getApplication()) }
            _songs.value = list
            withContext(Dispatchers.IO) { store.importFavoriteUris(_favorites.value) { u -> list.firstOrNull { it.uri == u } } }
            refreshLists()
        }
    }

    private fun currentSong(): Song? {
        val c = controller ?: return null
        return _queue.value.getOrNull(c.currentMediaItemIndex)?.song
    }

    private fun refresh() {
        val c = controller
        if (c == null) {
            _ui.value = _ui.value.copy(connected = false)
            return
        }
        var kbps: Int? = null
        var khz: Int? = null
        var stereo: Boolean? = null
        for (group in c.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || group.length == 0) continue
            val f = group.getTrackFormat(0)
            if (f.bitrate > 0) kbps = f.bitrate / 1000
            else if (f.averageBitrate > 0) kbps = f.averageBitrate / 1000
            if (f.sampleRate > 0) khz = f.sampleRate / 1000
            if (f.channelCount > 0) stereo = f.channelCount >= 2
            break
        }
        val song = currentSong()
        if (kbps == null) kbps = song?.estimatedKbps
        val md = c.mediaMetadata
        val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (song?.durationMs ?: 0L)
        _ui.value = _ui.value.copy(
            connected = true,
            title = md.title?.toString() ?: song?.title.orEmpty(),
            artist = md.artist?.toString() ?: song?.artist.orEmpty(),
            isPlaying = c.isPlaying,
            hasMedia = c.mediaItemCount > 0,
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = dur,
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
            kbps = kbps,
            khz = khz,
            stereo = stereo,
        )
    }

    private fun Song.toItem(): MediaItem = MediaItem.Builder()
        .setMediaId(uri)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build()
        )
        .build()

    /** Replace the queue with [list] and start playing at [start]. */
    fun playList(list: List<Song>, start: Int) {
        val c = controller ?: return
        _queue.value = list.map { entry(it) }
        _selection.value = emptySet()
        c.setMediaItems(list.map { it.toItem() }, start.coerceIn(0, (list.size - 1).coerceAtLeast(0)), 0L)
        c.prepare()
        c.play()
        saveQueueState()
    }

    fun playQueueIndex(i: Int) {
        val c = controller ?: return
        if (i in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(i)
            c.play()
        }
    }

    // ---- transport ----
    fun play() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            val all = _songs.value
            if (all.isNotEmpty()) playList(all, 0)
            return
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else play()
    }

    fun pause() { controller?.pause() }

    fun stop() {
        val c = controller ?: return
        c.pause()
        c.seekTo(0L)
    }

    fun next() {
        val c = controller ?: return
        when {
            c.hasNextMediaItem() -> c.seekToNextMediaItem()
            c.mediaItemCount > 0 -> c.seekToDefaultPosition(0)
        }
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun seekFraction(f: Float) {
        val c = controller ?: return
        val d = _ui.value.durationMs
        if (d > 0) c.seekTo((d * f.coerceIn(0f, 1f)).toLong())
    }

    // ---- settings ----
    fun setVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        controller?.volume = vol
        _ui.value = _ui.value.copy(volume = vol)
        prefs.edit().putFloat("volume", vol).apply()
    }

    fun setBalance(b: Float) {
        val bal = b.coerceIn(-1f, 1f)
        AudioEngine.balance = bal
        _ui.value = _ui.value.copy(balance = bal)
        prefs.edit().putFloat("balance", bal).apply()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        prefs.edit().putBoolean("shuffle", c.shuffleModeEnabled).apply()
        refresh()
    }

    fun cycleRepeat() {
        val c = controller ?: return
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        prefs.edit().putInt("repeat", when (next) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 }).apply()
        refresh()
    }

    fun toggleEq() {
        AudioEngine.eqEnabled = !AudioEngine.eqEnabled
        prefs.edit().putBoolean("eqOn", AudioEngine.eqEnabled).apply()
        _ui.value = _ui.value.copy(eqOn = AudioEngine.eqEnabled)
        _eq.value = _eq.value.copy(on = AudioEngine.eqEnabled)
    }

    // ---- equalizer ----
    fun setEqOn(on: Boolean) { if (AudioEngine.eqEnabled != on) toggleEq() }

    fun setEqAuto(auto: Boolean) {
        _eq.value = _eq.value.copy(auto = auto)
        prefs.edit().putBoolean("eqAuto", auto).apply()
        if (auto) controller?.currentMediaItem?.mediaId?.let { applyAutoPresetFor(it) }
    }

    fun toggleEqShade() { _eq.value = _eq.value.copy(shade = !_eq.value.shade) }

    private fun commitEq(bands: List<Float>, preamp: Float, name: String?) {
        AudioEngine.setAllGains(bands.toFloatArray())
        AudioEngine.preampDb = preamp
        _eq.value = _eq.value.copy(bands = bands, preamp = preamp, presetName = name)
        prefs.edit().apply {
            bands.forEachIndexed { i, v -> putFloat("eq$i", v) }
            putFloat("preamp", preamp)
            if (name != null) putString("eqPreset", name) else remove("eqPreset")
        }.apply()
        if (_eq.value.auto) rememberAutoPreset()
    }

    fun setBand(i: Int, db: Float) {
        val v = (db * 2f).let { kotlin.math.round(it) / 2f }.coerceIn(-12f, 12f)
        val bands = _eq.value.bands.toMutableList().also { it[i] = v }
        commitEq(bands, _eq.value.preamp, null)
    }

    fun setPreamp(db: Float) {
        val v = (db * 2f).let { kotlin.math.round(it) / 2f }.coerceIn(-12f, 12f)
        commitEq(_eq.value.bands, v, null)
    }

    fun applyPreset(p: EqPreset) = commitEq(p.bands, p.preamp, p.name)

    fun resetEq() = applyPreset(BuiltInPresets.byName("Flat")!!)

    private fun loadMine(): List<EqPreset> = runCatching {
        val arr = JSONArray(prefs.getString("myPresets", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("bands")
            EqPreset(o.getString("name"), (0 until 10).map { b.getDouble(it).toFloat() }, o.optDouble("preamp", 0.0).toFloat())
        }
    }.getOrDefault(emptyList())

    private fun saveMine(list: List<EqPreset>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("name", p.name).put("preamp", p.preamp.toDouble())
                .put("bands", JSONArray().apply { p.bands.forEach { put(it.toDouble()) } }))
        }
        prefs.edit().putString("myPresets", arr.toString()).apply()
        _eq.value = _eq.value.copy(mine = list)
    }

    /** Returns an error message, or null when saved. */
    fun saveMyPreset(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "A preset needs a name"
        val mine = _eq.value.mine
        if (mine.any { it.name.equals(n, true) }) return "There is already a preset called \"$n\""
        saveMine(mine + EqPreset(n, _eq.value.bands, _eq.value.preamp))
        _eq.value = _eq.value.copy(presetName = n)
        prefs.edit().putString("eqPreset", n).apply()
        return null
    }

    fun renameMyPreset(old: String, new: String): String? {
        val n = new.trim()
        if (n.isEmpty()) return "A preset needs a name"
        val mine = _eq.value.mine
        if (mine.any { it.name.equals(n, true) && !it.name.equals(old, true) }) return "There is already a preset called \"$n\""
        saveMine(mine.map { if (it.name == old) it.copy(name = n) else it })
        if (_eq.value.presetName == old) _eq.value = _eq.value.copy(presetName = n)
        return null
    }

    fun deleteMyPreset(name: String) {
        saveMine(_eq.value.mine.filterNot { it.name == name })
    }

    fun importEqf(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read the file")
                    if (bytes.size > 2_000_000) error("That file is too large to open")
                    Eqf.read(bytes)
                }
            }
            result.onSuccess { found ->
                val mine = _eq.value.mine.toMutableList()
                var added = 0
                for (p in found) {
                    var name = p.name
                    var k = 2
                    while (mine.any { it.name.equals(name, true) }) name = "${p.name} ($k)".also { k++ }
                    mine += p.copy(name = name)
                    added++
                }
                saveMine(mine)
                _message.value = "Added $added preset(s) to My presets"
            }.onFailure { _message.value = it.message ?: "Could not read those presets" }
        }
    }

    fun exportEqf(uri: Uri) {
        val mine = _eq.value.mine
        if (mine.isEmpty()) { _message.value = "Save a preset first: Export writes your own presets to an .eqf file."; return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(Eqf.write(mine)) } ?: error("x")
                }.isSuccess
            }
            _message.value = if (ok) "Exported ${mine.size} preset(s)" else "Could not write the file"
        }
    }

    /** Writes the presets to share into the cache and returns that file. */
    fun presetFileForSharing(onlyCurrent: Boolean): java.io.File? {
        val list = if (onlyCurrent) listOf(EqPreset(_eq.value.presetName ?: "My preset", _eq.value.bands, _eq.value.preamp))
        else _eq.value.mine
        if (list.isEmpty()) { _message.value = "Save a preset first."; return null }
        val dir = java.io.File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
        val safe = (if (onlyCurrent) list[0].name else "Klang presets").replace(Regex("[^\\p{L}\\p{N} ()._-]"), "_")
        return java.io.File(dir, "$safe.eqf").apply { writeBytes(Eqf.write(list)) }
    }

    // auto: remember a preset per track
    private fun autoMap(): JSONObject = runCatching { JSONObject(prefs.getString("eqAutoMap", "{}")!!) }.getOrDefault(JSONObject())

    private fun rememberAutoPreset() {
        val id = controller?.currentMediaItem?.mediaId ?: return
        val e = _eq.value
        val map = autoMap()
        map.put(id, JSONObject().put("name", e.presetName ?: JSONObject.NULL).put("preamp", e.preamp.toDouble())
            .put("bands", JSONArray().apply { e.bands.forEach { put(it.toDouble()) } }))
        prefs.edit().putString("eqAutoMap", map.toString()).apply()
    }

    private fun applyAutoPresetFor(id: String) {
        val o = autoMap().optJSONObject(id) ?: return
        val b = o.optJSONArray("bands") ?: return
        val bands = (0 until 10).map { b.optDouble(it, 0.0).toFloat() }
        val name = if (o.isNull("name")) null else o.optString("name")
        AudioEngine.setAllGains(bands.toFloatArray())
        AudioEngine.preampDb = o.optDouble("preamp", 0.0).toFloat()
        _eq.value = _eq.value.copy(bands = bands, preamp = AudioEngine.preampDb, presetName = name)
    }

    // ---- playlist editing ----
    // Every edit is applied to both [_queue] and the player timeline, in the same order.

    private fun app() = getApplication<Application>()

    private fun setQueue(list: List<QueueEntry>) {
        _queue.value = list
        _selection.value = _selection.value.filter { k -> list.any { it.key == k } }.toSet()
        saveQueueState()
    }

    fun appendSongs(songs: List<Song>, playNow: Boolean = false) {
        if (songs.isEmpty()) return
        val c = controller ?: return
        val start = _queue.value.size
        setQueue(_queue.value + songs.map { entry(it) })
        c.addMediaItems(songs.map { it.toItem() })
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (playNow) { c.seekToDefaultPosition(start); c.play() }
        _message.value = "Added ${songs.size} song(s)"
    }

    fun addUrl(url: String): String? {
        val u = url.trim()
        if (YouTube.isYouTube(u)) { openYouTubeLink(u, play = false); return null }
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "Type a link that starts with http:// or https://"
        appendSongs(listOf(songFromUrl(u)))
        return null
    }

    fun addDocuments(uris: List<Uri>) {
        viewModelScope.launch {
            val songs = withContext(Dispatchers.IO) {
                uris.map { keepAccess(app(), it); songFromDocument(app(), it) }
            }
            appendSongs(songs)
        }
    }

    fun addFolder(tree: Uri) {
        viewModelScope.launch {
            _message.value = "Adding…"
            val songs = withContext(Dispatchers.IO) {
                keepAccess(app(), tree)
                audioInTree(app(), tree).map { songFromDocument(app(), it) }
                    .sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
            }
            if (songs.isEmpty()) _message.value = "No music found in that folder." else appendSongs(songs)
        }
    }

    // selection
    fun select(key: Long) { _selection.value = setOf(key) }
    fun toggleSelect(key: Long) { _selection.value = _selection.value.let { if (key in it) it - key else it + key } }
    fun selectAll() { _selection.value = _queue.value.map { it.key }.toSet() }
    fun selectNone() { _selection.value = emptySet() }
    fun invertSelection() { _selection.value = _queue.value.map { it.key }.toSet() - _selection.value }

    fun playEntry(key: Long) {
        val i = _queue.value.indexOfFirst { it.key == key }
        if (i >= 0) playQueueIndex(i)
    }

    /** Removes the entries whose keys are in [keys], keeping playback going when possible. */
    private fun removeKeys(keys: Set<Long>) {
        if (keys.isEmpty()) return
        val c = controller ?: return
        val list = _queue.value
        // remove from the end so indices stay valid
        for (i in list.indices.reversed()) if (list[i].key in keys) c.removeMediaItem(i)
        setQueue(list.filterNot { it.key in keys })
    }

    fun removeSelected() {
        val n = _selection.value.size
        if (n == 0) { _message.value = "Select songs first (tap, or long-press for more)"; return }
        removeKeys(_selection.value); _message.value = "Removed $n"
    }

    fun cropToSelection() {
        if (_selection.value.isEmpty()) { _message.value = "Select the songs to keep first"; return }
        removeKeys(_queue.value.map { it.key }.toSet() - _selection.value)
    }

    fun removeAll() {
        val c = controller ?: return
        c.clearMediaItems()
        setQueue(emptyList())
    }

    fun removeDuplicates() {
        val seen = HashSet<String>()
        val dup = _queue.value.filterNot { seen.add(it.song.uri) }.map { it.key }.toSet()
        removeKeys(dup)
        _message.value = if (dup.isEmpty()) "No duplicate entries" else "Removed ${dup.size} duplicate(s)"
    }

    fun removeUnplayable() {
        viewModelScope.launch {
            val bad = withContext(Dispatchers.IO) { _queue.value.filterNot { isPlayable(app(), it.song) }.map { it.key }.toSet() }
            removeKeys(bad)
            _message.value = if (bad.isEmpty()) "Every entry can be played" else "Removed ${bad.size} unplayable entr${if (bad.size == 1) "y" else "ies"}"
        }
    }

    /** Reorders the queue to [target] using moves, so the current song keeps playing. */
    private fun applyOrder(target: List<QueueEntry>) {
        val c = controller ?: return
        val work = _queue.value.toMutableList()
        for (i in target.indices) {
            val j = work.indexOfFirst { it.key == target[i].key }
            if (j != i && j >= 0) {
                c.moveMediaItem(j, i)
                work.add(i, work.removeAt(j))
            }
        }
        setQueue(work)
    }

    fun sortByTitle() = applyOrder(_queue.value.sortedWith(compareBy({ it.song.artist.lowercase() }, { it.song.title.lowercase() })))
    fun sortByLength() = applyOrder(_queue.value.sortedBy { it.song.durationMs })
    fun reverseList() = applyOrder(_queue.value.reversed())
    fun randomizeList() = applyOrder(_queue.value.shuffled())

    fun renameEntry(key: Long, title: String): String? {
        val t = title.trim()
        if (t.isEmpty()) return "Type a name"
        val c = controller ?: return null
        val i = _queue.value.indexOfFirst { it.key == key }
        if (i < 0) return null
        val e = _queue.value[i]
        val ne = e.copy(song = e.song.copy(title = t))
        c.replaceMediaItem(i, ne.song.toItem())
        setQueue(_queue.value.toMutableList().also { it[i] = ne })
        return null
    }

    fun toggleFavorite(uri: String) {
        val on = uri !in _favorites.value
        val f = if (on) _favorites.value + uri else _favorites.value - uri
        _favorites.value = f
        prefs.edit().putStringSet("favorites", f).apply()
        val song = _queue.value.firstOrNull { it.song.uri == uri }?.song
            ?: _songs.value.firstOrNull { it.uri == uri }
            ?: _lists.value.favorites.firstOrNull { it.uri == uri }
        if (song != null) store.setFavorite(song, on)
        refreshLists()
    }

    fun favoriteCurrent() {
        val s = currentSong() ?: run { _message.value = "Play a song first"; return }
        toggleFavorite(s.uri)
        _message.value = if (s.uri in _favorites.value) "Added to favorites" else "Removed from favorites"
    }

    // ---- library: plays, history, favorites ----
    private fun refreshLists() {
        _lists.value = LibraryLists(store.favorites(), store.mostPlayed(), store.history())
    }

    /** A play counts once a track has played for 30 seconds (or half of a short track). */
    private fun countPlayIfNeeded() {
        val c = controller ?: return
        val entry = _queue.value.getOrNull(c.currentMediaItemIndex) ?: return
        if (countedKey == entry.key) return
        val dur = c.duration.takeIf { it > 0 } ?: entry.song.durationMs
        val need = if (dur > 0) minOf(30_000L, dur / 2) else 30_000L
        if (c.isPlaying && c.currentPosition >= need) {
            countedKey = entry.key
            val song = if (entry.song.durationMs <= 0 && dur > 0) entry.song.copy(durationMs = dur) else entry.song
            viewModelScope.launch(Dispatchers.IO) {
                store.recordPlay(song)
                withContext(Dispatchers.Main) { refreshLists() }
            }
        }
    }

    fun clearPlayHistory() {
        store.clearHistory()
        refreshLists()
        _message.value = "History cleared"
    }

    fun savedListSongs(name: String): List<Song> =
        savedLists.read(name)?.let { M3u.parse(it, _songs.value).first } ?: emptyList()

    // ---- lyrics ----
    fun loadLyrics(force: Boolean = false) {
        val c = controller
        val song = currentSong()
        if (c == null || song == null) {
            _lyrics.value = LyricsUi(message = "Play a song first to see its lyrics.")
            return
        }
        if (!force && _lyrics.value.forUri == song.uri && !_lyrics.value.loading) return
        val title = c.mediaMetadata.title?.toString() ?: song.title
        val artist = c.mediaMetadata.artist?.toString() ?: song.artist
        val dur = c.duration.takeIf { it > 0 } ?: song.durationMs
        _lyrics.value = LyricsUi(forUri = song.uri, loading = true, message = "Searching LRCLIB…")
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { LrcLib.find(title, artist, dur) } }
            if (_lyrics.value.forUri != song.uri) return@launch
            r.onSuccess { res ->
                _lyrics.value = LyricsUi(
                    forUri = song.uri, synced = res.synced, plain = res.plain,
                    source = listOf(res.artistName, res.trackName).filter { it.isNotBlank() }.joinToString(" – "),
                )
            }.onFailure {
                _lyrics.value = LyricsUi(forUri = song.uri, message = (it as? LyricsUnavailable)?.message ?: "No lyrics found for this song.")
            }
        }
    }

    fun seekToMs(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0L)) }

    // lists
    fun newList(name: String): String? {
        val n = name.trim().ifEmpty { return "Type a name" }
        removeAll()
        _listName.value = n
        prefs.edit().putString("listName", n).apply()
        return null
    }

    fun saveList(name: String): String? {
        val n = name.trim().ifEmpty { return "Playlist name:" }
        if (_queue.value.isEmpty()) return "The playlist is empty"
        savedLists.save(n, _queue.value.map { it.song })
        _savedNames.value = savedLists.names()
        _listName.value = n
        prefs.edit().putString("listName", n).apply()
        _message.value = "Saved as \"$n\""
        return null
    }

    fun loadList(name: String, append: Boolean) {
        val text = savedLists.read(name) ?: return
        val (songs, missing) = M3u.parse(text, _songs.value)
        if (songs.isEmpty()) { _message.value = "That playlist has no songs Klang can play."; return }
        if (append) appendSongs(songs) else {
            playList(songs, 0)
            _listName.value = name
            prefs.edit().putString("listName", name).apply()
        }
        if (missing > 0) _message.value = "$missing song(s) could not be found and were left out."
    }

    fun deleteList(name: String) {
        savedLists.delete(name)
        _savedNames.value = savedLists.names()
    }

    fun importM3u(uri: Uri, append: Boolean) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { app().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (text == null) { _message.value = "Could not open that file"; return@launch }
            val (songs, missing) = M3u.parse(text, _songs.value)
            if (songs.isEmpty()) { _message.value = "That playlist has no songs Klang can play."; return@launch }
            if (append) appendSongs(songs) else playList(songs, 0)
            if (missing > 0) _message.value = "$missing song(s) could not be found and were left out."
        }
    }

    /** Writes the current playlist to a shareable .m3u file in the cache. */
    fun playlistFileForSharing(): java.io.File? {
        if (_queue.value.isEmpty()) { _message.value = "The playlist is empty"; return null }
        val dir = java.io.File(app().cacheDir, "share").apply { mkdirs() }
        val safe = _listName.value.replace(Regex("[^\\p{L}\\p{N} ()._-]"), "_")
        return java.io.File(dir, "$safe.m3u").apply { writeText(M3u.write(_queue.value.map { it.song })) }
    }

    fun playlistLinksText(): String =
        _queue.value.joinToString("\n") { e ->
            val s = e.song
            (if (s.artist.isNotBlank()) "${s.artist} - ${s.title}" else s.title) + if (s.uri.startsWith("http")) "\n${s.uri}" else ""
        }

    // persistence of the current queue
    private fun saveQueueState() {
        val c = controller
        val arr = JSONArray()
        _queue.value.forEach { e ->
            val s = e.song
            arr.put(JSONObject().put("id", s.id).put("uri", s.uri).put("t", s.title).put("a", s.artist)
                .put("al", s.album).put("d", s.durationMs).put("z", s.sizeBytes))
        }
        prefs.edit().putString("queue", arr.toString())
            .putInt("queueIndex", c?.currentMediaItemIndex ?: 0)
            .putLong("queuePos", c?.currentPosition ?: 0L)
            .apply()
    }

    private fun restoreQueue(c: MediaController) {
        if (c.mediaItemCount > 0) {
            // The service kept playing while the screen was gone: rebuild our list from it.
            val list = (0 until c.mediaItemCount).map { i ->
                val item = c.getMediaItemAt(i)
                val md = item.mediaMetadata
                val known = _songs.value.firstOrNull { it.uri == item.mediaId }
                entry(known ?: Song(-1L - i, item.mediaId, md.title?.toString() ?: item.mediaId, md.artist?.toString().orEmpty(), md.albumTitle?.toString().orEmpty(), 0L, 0L))
            }
            _queue.value = list
            return
        }
        val saved = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrNull() ?: return
        if (saved.length() == 0) return
        val songs = (0 until saved.length()).map { i ->
            val o = saved.getJSONObject(i)
            Song(o.optLong("id"), o.getString("uri"), o.optString("t"), o.optString("a"), o.optString("al"), o.optLong("d"), o.optLong("z"))
        }
        _queue.value = songs.map { entry(it) }
        val idx = prefs.getInt("queueIndex", 0).coerceIn(0, songs.size - 1)
        c.setMediaItems(songs.map { it.toItem() }, idx, prefs.getLong("queuePos", 0L))
        c.prepare()
    }

    // ---- YouTube ----

    private fun ytSong(it: YtItem): Song {
        val id = YouTube.videoId(it.url)
        val url = id?.let { v -> YouTube.watchUrl(v) } ?: it.url
        return Song(-((url.hashCode().toLong() and 0xffffffffL) + 1L), url, it.title, it.uploader, "YouTube", it.durationSec * 1000L, 0L)
    }

    fun setQuery(q: String) {
        _search.value = _search.value.copy(query = q, error = null)
    }

    fun loadSuggestions(q: String) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { YouTube.suggestions(q) }
            if (_search.value.query == q) _search.value = _search.value.copy(suggestions = list.take(8))
        }
    }

    /** Search box: a video, playlist or channel link is opened directly; anything else is searched. */
    fun submitSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        _search.value = _search.value.copy(query = q, suggestions = emptyList())
        if (YouTube.isYouTube(q)) { openYouTubeLink(q, play = true); return }
        rememberQuery(q)
        _search.value = _search.value.copy(loading = true, error = null, heading = q)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YouTube.search(q) } }
            r.onSuccess { list ->
                _search.value = _search.value.copy(loading = false, results = list, error = if (list.isEmpty()) "No videos found" else null)
            }.onFailure {
                _search.value = _search.value.copy(loading = false, error = it.message ?: "Search failed (the app may need updating)")
            }
        }
    }

    private fun openYouTubeLink(url: String, play: Boolean) {
        when {
            YouTube.isVideoLink(url) && !(YouTube.isPlaylistLink(url) && url.contains("/playlist")) -> {
                viewModelScope.launch {
                    _search.value = _search.value.copy(loading = true, error = null)
                    val r = withContext(Dispatchers.IO) { runCatching { YouTube.resolve(url) } }
                    _search.value = _search.value.copy(loading = false)
                    r.onSuccess { res ->
                        appendSongs(listOf(ytSong(YtItem(res.pageUrl, res.title, res.uploader, res.durationSec, null))), playNow = play)
                    }.onFailure { _message.value = it.message ?: "Could not add that link" }
                }
            }
            YouTube.isPlaylistLink(url) -> importYouTubePlaylist(url)
            YouTube.isChannelLink(url) -> addBookmark(url)
            else -> _message.value = "Klang doesn't understand that link."
        }
    }

    private fun importYouTubePlaylist(url: String) {
        _message.value = "Importing playlist…"
        viewModelScope.launch {
            var first = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    YouTube.importPlaylist(url) { page ->
                        viewModelScope.launch {
                            appendSongs(page.map { ytSong(it) }, playNow = first && controller?.isPlaying != true)
                            first = false
                        }
                    }
                }
            }
            r.onFailure { _message.value = it.message ?: "Could not load that channel or playlist" }
        }
    }

    fun playResult(item: YtItem) = appendSongs(listOf(ytSong(item)), playNow = true)
    fun addResult(item: YtItem) = appendSongs(listOf(ytSong(item)))
    fun addAllResults() = appendSongs(_search.value.results.map { ytSong(it) })

    // history
    private fun loadHistory(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("searchHistory", "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun rememberQuery(q: String) {
        val h = (listOf(q) + _search.value.history.filterNot { it.equals(q, true) }).take(20)
        prefs.edit().putString("searchHistory", JSONArray(h).toString()).apply()
        _search.value = _search.value.copy(history = h)
    }

    fun clearHistory() {
        prefs.edit().remove("searchHistory").apply()
        _search.value = _search.value.copy(history = emptyList())
    }

    // bookmarks
    private fun loadBookmarks(): List<Bookmark> = runCatching {
        val a = JSONArray(prefs.getString("bookmarks", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Bookmark(o.getString("n"), o.getString("u"), o.optBoolean("c")) }
    }.getOrDefault(emptyList())

    private fun saveBookmarks(list: List<Bookmark>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("u", it.url).put("c", it.isChannel)) }
        prefs.edit().putString("bookmarks", a.toString()).apply()
        _search.value = _search.value.copy(bookmarks = list)
    }

    fun addBookmark(url: String) {
        _search.value = _search.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YouTube.bookmarkInfo(url) } }
            r.onSuccess { (b, videos) ->
                if (_search.value.bookmarks.none { it.url == b.url }) saveBookmarks(_search.value.bookmarks + b)
                _search.value = _search.value.copy(loading = false, heading = b.name, results = videos)
                _message.value = "Bookmarked ${b.name}"
            }.onFailure {
                _search.value = _search.value.copy(loading = false, error = it.message ?: "Could not load that channel or playlist")
            }
        }
    }

    fun openBookmark(b: Bookmark) {
        _search.value = _search.value.copy(loading = true, error = null, heading = b.name, results = emptyList())
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YouTube.bookmarkInfo(b.url) } }
            r.onSuccess { (_, videos) ->
                _search.value = _search.value.copy(loading = false, results = videos, error = if (videos.isEmpty()) "No videos" else null)
            }.onFailure { _search.value = _search.value.copy(loading = false, error = it.message ?: "Could not load") }
        }
    }

    fun deleteBookmark(b: Bookmark) = saveBookmarks(_search.value.bookmarks.filterNot { it.url == b.url })

    // skip non-music parts (SponsorBlock "music_offtopic")
    fun setSkipNonMusic(on: Boolean) {
        _skipNonMusic.value = on
        prefs.edit().putBoolean("skipNonMusic", on).apply()
    }

    private fun skipSegmentsIfNeeded() {
        if (!_skipNonMusic.value) return
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId?.let { YouTube.videoId(it) } ?: return
        val segs = YouTube.segmentsCached(id)
        if (segs == null) {
            if (segmentLoads.add(id)) viewModelScope.launch(Dispatchers.IO) { YouTube.loadSegments(id) }
            return
        }
        if (!c.isPlaying) return
        val pos = c.currentPosition / 1000.0
        val hit = segs.firstOrNull { pos >= it.startSec && pos < it.endSec - 0.5 } ?: return
        val dur = c.duration
        val target = (hit.endSec * 1000).toLong()
        if (dur > 0 && target >= dur - 1000) next() else c.seekTo(target)
        _message.value = "Skipped a non-music part"
    }

    // ---- sleep timer ----
    fun setSleep(choice: Int) {
        val i = choice.coerceIn(0, SLEEP_CHOICES.lastIndex)
        _sleep.value = i
        sleepJob?.cancel()
        _sleepLeftMs.value = 0L
        val minutes = SLEEP_MINUTES[i]
        if (minutes > 0) {
            val end = System.currentTimeMillis() + minutes * 60_000L
            sleepJob = viewModelScope.launch {
                while (true) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) break
                    _sleepLeftMs.value = left
                    delay(1_000)
                }
                controller?.pause()
                _sleep.value = 0
                _sleepLeftMs.value = 0L
            }
        }
    }

    fun cycleSleep() = setSleep(if (_sleep.value >= SLEEP_CHOICES.lastIndex) 0 else _sleep.value + 1)

    override fun onCleared() {
        saveQueueState()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
