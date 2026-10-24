package app.klang

import android.Manifest
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.klang.ui.K
import app.klang.ui.LibraryScreen
import app.klang.ui.PlayerActions
import app.klang.ui.PlayerScreen
import app.klang.ui.QueueScreen
import app.klang.ui.Screen

class MainActivity : ComponentActivity() {

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

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
                        PlayerScreen(ui, sleep, sleepLeft, songs.size, actions)
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
