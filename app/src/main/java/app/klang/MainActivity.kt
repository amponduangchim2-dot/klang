package app.klang

import android.Manifest
import android.content.Intent
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
import app.klang.ui.LibraryScreen
import app.klang.ui.PlayerActions
import app.klang.ui.PlayerScreen
import app.klang.ui.QueueScreen
import app.klang.ui.Screen

class MainActivity : ComponentActivity() {

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun sharePresets(vm: PlayerViewModel) {
        val file = vm.presetFileForSharing(onlyCurrent = true) ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${file.nameWithoutExtension}: a Winamp EQ preset. Open it with Klang or Winamp.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share preset"))
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

            BackHandler(enabled = screen != Screen.Player) { screen = Screen.Player }

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
                    open = { screen = it },
                    notYet = { what -> Toast.makeText(this, "$what arrives in the next build", Toast.LENGTH_SHORT).show() },
                )
            }

            val currentUri = queue.getOrNull(ui.index)?.uri

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
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                when (screen) {
                    Screen.Player -> Box(Modifier.verticalScroll(rememberScrollState())) {
                        PlayerScreen(ui, sleep, sleepLeft, songs.size, eq.bands, eq.presetName, actions)
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
                            )
                        })
                    }
                    Screen.Queue -> QueueScreen(
                        queue = queue,
                        currentIndex = ui.index,
                        currentUri = currentUri,
                        onPick = { vm.playQueueIndex(it) },
                        onBack = { screen = Screen.Player },
                        onOpenLibrary = { screen = Screen.Library },
                    )
                }
            }
            }
        }
    }
}
