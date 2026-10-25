package app.klang

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.klang.ui.EqActions
import app.klang.ui.EqScreen
import app.klang.ui.K
import app.klang.ui.SearchActions
import app.klang.ui.SearchScreen
import app.klang.ui.PlaylistActions
import app.klang.ui.PlaylistScreen
import app.klang.ui.LibraryScreen
import app.klang.ui.PlayerActions
import app.klang.ui.PlayerScreen
import app.klang.ui.Screen
import app.klang.ui.HubActions
import app.klang.ui.LibraryHub
import app.klang.ui.ListKind
import app.klang.ui.LyricsScreen
import app.klang.ui.SongListScreen
import app.klang.ui.ago
import app.klang.ui.OptionsActions
import app.klang.ui.OptionsScreen
import app.klang.ui.ShareScreen
import app.klang.ui.SkinsScreen
import app.klang.share.CardFormat
import app.klang.share.CardInfo
import app.klang.share.ShareCard
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun sharePresets(vm: PlayerViewModel) {
        val file = vm.presetFileForSharing(onlyCurrent = true) ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${file.nameWithoutExtension}: a Winamp EQ preset. Open it with Klang Audio or Winamp.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share preset"))
    }

    private fun shareFile(file: java.io.File, mime: String, text: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, title))
    }

    /** The equalizer screen of whichever outside EQ app is installed, attached to Klang's audio. */
    private fun openExternalEq() {
        val i = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, app.klang.playback.AudioEngine.audioSessionId)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
        if (app.klang.playback.AudioEngine.audioSessionId == 0) {
            Toast.makeText(this, "Play a song first, then open the equalizer app.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { @Suppress("DEPRECATION") startActivityForResult(i, 77) }.onFailure {
            Toast.makeText(this, "No equalizer app found. Install one such as Wavelet or Equalizer FX.", Toast.LENGTH_LONG).show()
        }
    }

    /** The phone's own "play on" picker: Bluetooth, wired, and speakers the phone can cast to. */
    private fun openOutputPicker() {
        val ok = when {
            Build.VERSION.SDK_INT >= 34 -> runCatching { android.media.MediaRouter2.getInstance(this).showSystemOutputSwitcher() }.getOrDefault(false)
            Build.VERSION.SDK_INT >= 30 -> runCatching {
                sendBroadcast(Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui")
                    .putExtra("package_name", packageName))
                true
            }.getOrDefault(false)
            else -> false
        }
        if (!ok) runCatching { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show() }
    }

    private fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, title))
    }

    private val playerVm: PlayerViewModel get() = androidx.lifecycle.ViewModelProvider(this)[PlayerViewModel::class.java]

    override fun onStart() {
        super.onStart()
        playerVm.visible = true
    }

    override fun onStop() {
        playerVm.visible = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            val vm: PlayerViewModel = viewModel()
            val ui by vm.ui.collectAsStateWithLifecycle()
            val songs by vm.songs.collectAsStateWithLifecycle()
            val queue by vm.queue.collectAsStateWithLifecycle()
            val sleep by vm.sleepChoice.collectAsStateWithLifecycle()
            val sleepLeft by vm.sleepLeftMs.collectAsStateWithLifecycle()
            val eq by vm.eq.collectAsStateWithLifecycle()
            val message by vm.message.collectAsStateWithLifecycle()
            val selection by vm.selection.collectAsStateWithLifecycle()
            val favorites by vm.favorites.collectAsStateWithLifecycle()
            val listName by vm.listName.collectAsStateWithLifecycle()
            val savedNames by vm.savedNames.collectAsStateWithLifecycle()
            val searchUi by vm.search.collectAsStateWithLifecycle()
            val skipNonMusic by vm.skipNonMusic.collectAsStateWithLifecycle()
            val lists by vm.lists.collectAsStateWithLifecycle()
            val lyrics by vm.lyrics.collectAsStateWithLifecycle()
            var listKind by rememberSaveable { mutableStateOf(ListKind.FAVORITES) }
            val options by vm.options.collectAsStateWithLifecycle()
            var shareFormat by rememberSaveable { mutableIntStateOf(0) }

            // system bars follow the skin
            val darkSkin = K.skin.dark
            LaunchedEffect(darkSkin) {
                val style = if (darkSkin) SystemBarStyle.dark(AColor.TRANSPARENT)
                else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LaunchedEffect(options.keepScreenOn) {
                if (options.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!text.isNullOrBlank()) { vm.setQuery(text); vm.submitSearch(text) }
            }
            val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty()) vm.addDocuments(uris)
            }
            val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) vm.addFolder(uri)
            }
            val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.importM3u(uri, append = false)
            }
            LaunchedEffect(message) {
                message?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    vm.messageShown()
                }
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.importEqf(uri)
            }
            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                if (uri != null) vm.exportEqf(uri)
            }
            val eqActions = remember(vm) {
                EqActions(
                    setOn = vm::setEqOn,
                    setAuto = vm::setEqAuto,
                    setBand = vm::setBand,
                    setPreamp = vm::setPreamp,
                    apply = vm::applyPreset,
                    reset = vm::resetEq,
                    toggleShade = vm::toggleEqShade,
                    save = vm::saveMyPreset,
                    rename = vm::renameMyPreset,
                    delete = vm::deleteMyPreset,
                    importFile = { importLauncher.launch(arrayOf("*/*")) },
                    exportFile = {
                        if (vm.eq.value.mine.isEmpty()) Toast.makeText(this@MainActivity, "Save a preset first: Export writes your own presets to an .eqf file.", Toast.LENGTH_LONG).show()
                        else exportLauncher.launch("Klang presets.eqf")
                    },
                    share = { sharePresets(vm) },
                    back = { },
                )
            }
            var screen by rememberSaveable { mutableStateOf(Screen.Player) }
            var cameFromOptions by rememberSaveable { mutableStateOf(false) }
            var hasPermission by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED)
            }

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                hasPermission = result[audioPermission] == true ||
                    ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) vm.loadLibrary()
            }
            val askPermission = {
                val wanted = mutableListOf(audioPermission)
                if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(wanted.toTypedArray())
            }

            LaunchedEffect(Unit) {
                if (hasPermission) vm.loadLibrary() else askPermission()
            }

            BackHandler(enabled = screen != Screen.Player) {
                screen = when (screen) {
                    Screen.LibraryAdd -> Screen.Queue
                    Screen.Songs, Screen.Bookmarks -> Screen.Hub
                    Screen.Skins -> if (cameFromOptions) Screen.Options else Screen.Player
                    else -> Screen.Player
                }
            }

            val actions = remember(vm) {
                PlayerActions(
                    togglePlay = vm::togglePlay,
                    pause = vm::pause,
                    stop = vm::stop,
                    next = vm::next,
                    previous = vm::previous,
                    seek = vm::seekFraction,
                    setVolume = vm::setVolume,
                    setBalance = vm::setBalance,
                    toggleShuffle = vm::toggleShuffle,
                    cycleRepeat = vm::cycleRepeat,
                    toggleEq = vm::toggleEq,
                    cycleSleep = vm::cycleSleep,
                    open = { cameFromOptions = false; screen = it },
                    notYet = { what -> if (what == "Cast") openOutputPicker() else Toast.makeText(this, "$what arrives in the next build", Toast.LENGTH_SHORT).show() },
                )
            }

            val currentUri = queue.getOrNull(ui.index)?.song?.uri

            val searchActions = remember(vm) {
                SearchActions(
                    back = { screen = Screen.Player },
                    setQuery = vm::setQuery,
                    suggest = vm::loadSuggestions,
                    submit = vm::submitSearch,
                    voice = {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song or an artist")
                        runCatching { voiceLauncher.launch(i) }.onFailure {
                            Toast.makeText(this@MainActivity, "Voice search is not available on this phone", Toast.LENGTH_SHORT).show()
                        }
                    },
                    play = vm::playResult,
                    add = vm::addResult,
                    addAll = vm::addAllResults,
                    clearHistory = vm::clearHistory,
                    openBookmark = vm::openBookmark,
                    deleteBookmark = vm::deleteBookmark,
                    addBookmark = vm::addBookmark,
                    toggleSkip = { vm.setSkipNonMusic(!vm.skipNonMusic.value) },
                )
            }

            val playlistActions = remember(vm) {
                PlaylistActions(
                    back = { screen = Screen.Player },
                    tap = { key ->
                        val sel = vm.selection.value
                        if (sel.size == 1 && key in sel) vm.playEntry(key) else vm.select(key)
                    },
                    longPress = vm::toggleSelect,
                    toggleFavorite = vm::toggleFavorite,
                    addUrl = vm::addUrl,
                    addFolder = { folderLauncher.launch(null) },
                    addFiles = { filesLauncher.launch(arrayOf("audio/*")) },
                    addFromLibrary = { screen = Screen.LibraryAdd },
                    removeSelected = vm::removeSelected,
                    crop = vm::cropToSelection,
                    removeAll = vm::removeAll,
                    removeDuplicates = vm::removeDuplicates,
                    removeUnplayable = vm::removeUnplayable,
                    selectAll = vm::selectAll,
                    selectNone = vm::selectNone,
                    invert = vm::invertSelection,
                    sortTitle = vm::sortByTitle,
                    sortLength = vm::sortByLength,
                    reverse = vm::reverseList,
                    randomize = vm::randomizeList,
                    rename = vm::renameEntry,
                    needOne = { Toast.makeText(this@MainActivity, "Select one song first", Toast.LENGTH_SHORT).show() },
                    newList = vm::newList,
                    saveList = vm::saveList,
                    loadList = vm::loadList,
                    deleteList = vm::deleteList,
                    importM3u = { m3uLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "*/*")) },
                    shareM3u = {
                        vm.playlistFileForSharing()?.let { f ->
                            shareFile(f, "audio/x-mpegurl", "${f.nameWithoutExtension}: a playlist. Open it with Klang Audio or Winamp.", "Share playlist")
                        }
                    },
                    shareLinks = { shareText(vm.playlistLinksText(), "Share playlist") },
                    previous = vm::previous,
                    togglePlay = vm::togglePlay,
                )
            }

            // Keep the device face in proportion: cap the system font scale at 1.0
            // (the product design has fixed text sizes, like printed labels).
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, minOf(baseDensity.fontScale, 1f))
            ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(K.shell)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                when (screen) {
                    Screen.Player -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        // The face needs about 680 dp of height. On shorter screens (small phones, or a
                        // large display-size setting) everything is drawn a little smaller, in proportion,
                        // so it still fits without scrolling. Only very short screens scroll.
                        val need = 680.dp
                        val scale = (maxHeight / need).coerceAtMost(1f)
                        if (scale >= 0.72f) {
                            val d = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) {
                                PlayerScreen(ui, sleep, sleepLeft, songs.size, eq.bands, eq.presetName, actions, big = options.bigControls, fit = true)
                            }
                        } else Box(Modifier.verticalScroll(rememberScrollState())) {
                            PlayerScreen(ui, sleep, sleepLeft, songs.size, eq.bands, eq.presetName, actions, big = options.bigControls)
                        }
                    }
                    Screen.Library -> LibraryScreen(
                        songs = songs,
                        hasPermission = hasPermission,
                        currentUri = currentUri,
                        onAskPermission = askPermission,
                        onPlay = { list, i -> vm.playList(list, i); screen = Screen.Player },
                        onShuffleAll = {
                            if (songs.isNotEmpty()) {
                                vm.playList(songs.shuffled(), 0)
                                screen = Screen.Player
                            }
                        },
                        onBack = { screen = Screen.Player },
                    )
                    Screen.Eq -> Box(Modifier.verticalScroll(rememberScrollState())) {
                        EqScreen(eq, remember(eqActions) {
                            EqActions(
                                eqActions.setOn, eqActions.setAuto, eqActions.setBand, eqActions.setPreamp,
                                eqActions.apply, eqActions.reset, eqActions.toggleShade, eqActions.save,
                                eqActions.rename, eqActions.delete, eqActions.importFile, eqActions.exportFile,
                                eqActions.share, back = { screen = Screen.Player },
                                external = { openExternalEq() },
                            )
                        })
                    }
                    Screen.Search -> SearchScreen(searchUi, skipNonMusic, searchActions)
                    Screen.Bookmarks -> SearchScreen(searchUi, skipNonMusic, remember(searchActions) {
                        SearchActions(
                            { screen = Screen.Hub }, searchActions.setQuery, searchActions.suggest, searchActions.submit,
                            searchActions.voice, searchActions.play, searchActions.add, searchActions.addAll,
                            searchActions.clearHistory, searchActions.openBookmark, searchActions.deleteBookmark,
                            searchActions.addBookmark, searchActions.toggleSkip,
                        )
                    }, startTab = 1)
                    Screen.Hub -> LibraryHub(
                        localCount = songs.size,
                        favorites = lists.favorites.size,
                        mostPlayedTop = lists.mostPlayed.firstOrNull()?.first,
                        historyCount = lists.history.size,
                        lastPlayed = lists.history.firstOrNull()?.first,
                        bookmarks = searchUi.bookmarks.size,
                        savedNames = savedNames,
                        a = remember(vm) {
                            HubActions(
                                back = { screen = Screen.Player },
                                openLocal = { screen = Screen.Library },
                                openList = { listKind = it; screen = Screen.Songs },
                                openBookmarks = { screen = Screen.Bookmarks },
                                playSaved = { vm.loadList(it, false); screen = Screen.Player },
                                appendSaved = { vm.loadList(it, true) },
                                deleteSaved = vm::deleteList,
                            )
                        },
                    )
                    Screen.Songs -> {
                        val back = { screen = Screen.Hub }
                        val play = { l: List<app.klang.library.Song>, i: Int -> vm.playList(l, i); screen = Screen.Player }
                        val add = { l: List<app.klang.library.Song> -> vm.appendSongs(l) }
                        when {
                            listKind == ListKind.FAVORITES -> SongListScreen(
                                "favorites", "${lists.favorites.size} songs", lists.favorites, currentUri, null,
                                "No favorites yet. Tap ☆ next to a song in the playlist to star it.", play, add, back
                            )
                            listKind == ListKind.MOST -> {
                                val l = lists.mostPlayed
                                SongListScreen(
                                    "most played", "top ${l.size}", l.map { it.first }, currentUri, { i -> "${l[i].second}×" },
                                    "Nothing played yet. A song counts after 30 seconds.", play, add, back
                                )
                            }
                            listKind == ListKind.HISTORY -> {
                                val l = lists.history
                                SongListScreen(
                                    "history", "${l.size} recent", l.map { it.first }, currentUri, { i -> ago(l[i].second) },
                                    "Nothing played yet.", play, add, back,
                                    extraLabel = "clear", onExtra = vm::clearPlayHistory,
                                )
                            }
                            else -> {
                                val name = listKind.removePrefix("list:")
                                val l = remember(name, savedNames) { vm.savedListSongs(name) }
                                SongListScreen(
                                    name, "saved list", l, currentUri, null,
                                    "This list is empty, or its songs are no longer on this phone.", play, add, back
                                )
                            }
                        }
                    }
                    Screen.Skins -> SkinsScreen(
                        current = options.skin,
                        onPick = vm::setSkin,
                        onBack = { screen = if (cameFromOptions) Screen.Options else Screen.Player },
                    )
                    Screen.Options -> OptionsScreen(options, skipNonMusic, remember(vm) {
                        OptionsActions(
                            back = { screen = Screen.Player },
                            setFade = vm::setFade,
                            setBig = vm::setBigControls,
                            setKeepOn = vm::setKeepScreenOn,
                            setSkipTalk = vm::setSkipNonMusic,
                            setYtOpus = vm::setYtOpus,
                            openSkins = { cameFromOptions = true; screen = Screen.Skins },
                            checkUpdate = vm::checkForUpdate,
                            download = { url -> openUrl(url) },
                            openSource = { openUrl("https://github.com/amponduangchim2-dot/klang") },
                        )
                    })
                    Screen.Share -> {
                        val format = CardFormat.entries[shareFormat]
                        val skinNow = K.skin
                        fun cardInfo() = CardInfo(
                            title = ui.title, artist = ui.artist,
                            positionMs = ui.positionMs, durationMs = ui.durationMs,
                            source = vm.currentLink()?.removePrefix("https://"),
                            eqLabel = if (ui.eqOn) eq.presetName else null,
                        )
                        val preview by produceState<android.graphics.Bitmap?>(null, format, ui.title, ui.artist, skinNow) {
                            val info = cardInfo()
                            value = withContext(Dispatchers.Default) {
                                runCatching { ShareCard.render(this@MainActivity, info, format, skinNow) }.getOrNull()
                            }
                        }
                        ShareScreen(
                            preview = preview,
                            formatIndex = shareFormat,
                            hasSong = ui.hasMedia && ui.title.isNotBlank(),
                            link = vm.currentLink(),
                            onFormat = { shareFormat = it },
                            shareImage = {
                                runCatching {
                                    val bmp = ShareCard.render(this@MainActivity, cardInfo(), format, K.skin)
                                    val f = ShareCard.save(this@MainActivity, bmp, format)
                                    shareFile(f, "image/png", vm.nowPlayingText() ?: "", "Share now playing")
                                }.onFailure { Toast.makeText(this@MainActivity, "Could not make the card", Toast.LENGTH_SHORT).show() }
                            },
                            shareText = { vm.nowPlayingText()?.let { shareText(it, "Share now playing") } },
                            copyLink = {
                                vm.currentLink()?.let { l ->
                                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Klang Audio link", l))
                                    Toast.makeText(this@MainActivity, "Link copied", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onBack = { screen = Screen.Player },
                        )
                    }
                    Screen.Lyrics -> LyricsScreen(
                        ui = ui,
                        lyrics = lyrics,
                        currentUri = currentUri,
                        load = vm::loadLyrics,
                        seekTo = vm::seekToMs,
                        togglePlay = vm::togglePlay,
                        previous = vm::previous,
                        next = vm::next,
                        onBack = { screen = Screen.Player },
                    )
                    Screen.LibraryAdd -> LibraryScreen(
                        songs = songs,
                        hasPermission = hasPermission,
                        currentUri = currentUri,
                        onAskPermission = askPermission,
                        onPlay = { _, _ -> },
                        onShuffleAll = {},
                        onBack = { screen = Screen.Queue },
                        addMode = true,
                        onAdd = { vm.appendSongs(it) },
                    )
                    Screen.Queue -> PlaylistScreen(
                        queue = queue,
                        currentIndex = ui.index,
                        selection = selection,
                        favorites = favorites,
                        listName = listName,
                        savedNames = savedNames,
                        ui = ui,
                        a = playlistActions,
                    )
                }
            }
            }
        }
    }
}
