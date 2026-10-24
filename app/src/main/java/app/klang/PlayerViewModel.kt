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
import app.klang.library.LocalMusic
import app.klang.library.Song
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

/** Sleep timer choices, same set as the original app. */
val SLEEP_CHOICES = listOf("off", "15 min", "30 min", "45 min", "60 min", "90 min", "end of song")
private val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60, 90, -1)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("klang", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    private val _sleep = MutableStateFlow(0)
    val sleepChoice: StateFlow<Int> = _sleep
    private var sleepJob: Job? = null

    private val _sleepLeftMs = MutableStateFlow(0L)
    val sleepLeftMs: StateFlow<Long> = _sleepLeftMs

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
        connect()
        viewModelScope.launch {
            while (true) {
                refresh()
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
            refresh()
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun loadLibrary() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { LocalMusic.load(getApplication()) }
            _songs.value = list
        }
    }

    private fun currentSong(): Song? {
        val c = controller ?: return null
        val id = c.currentMediaItem?.mediaId ?: return null
        return _queue.value.firstOrNull { it.uri == id } ?: _songs.value.firstOrNull { it.uri == id }
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
        _queue.value = list
        c.setMediaItems(list.map { it.toItem() }, start.coerceIn(0, (list.size - 1).coerceAtLeast(0)), 0L)
        c.prepare()
        c.play()
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
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
