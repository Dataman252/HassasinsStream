package com.wifistream.receiver

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    // The same view model the screens use.
    private val model: AppViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        model.onResume()
        // Coming back to the app looks for a new version too (at most once an hour).
        lifecycleScope.launch { Updater.check(applicationContext) }
    }

    override fun onStop() {
        super.onStop()
        // A new app icon is switched in once the app is off screen, so the switch cannot close it.
        if (!isChangingConfigurations) AppTheme.applyPendingIcon(applicationContext)
    }

    // Leaving the app while a video plays on a phone shrinks it into a small floating window.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlayerSession.pipAllowed) enterPip()
    }

    fun enterPip() {
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(PlayerSession.pipAspect).build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerSession.inPip = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            getSystemService(UiModeManager::class.java).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        LocalIsTvSnapshot.realTv = isTv
        LocalIsTvSnapshot.isTv = isTv
        AppTheme.load(this)

        setContent {
            ReceiverTheme {
                // Created before the intro so finding the other device starts while it plays.
                val vm: AppViewModel = viewModel()
                AppTheme.systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                val playing = vm.backStack.last().let { it is Screen.Player || it is Screen.CompatPlayer }
                AppTheme.forceDark = playing
                // Dark status-bar icons on the light theme, white ones on the dark theme.
                val lightBars = AppTheme.light
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                // Phones stay upright everywhere except the player; coming back from a video turns them back.
                val bigScreenNow = isTv || androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
                // The TV layout test mode on a phone is landscape, like a TV.
                val testingTv = AppTheme.tvLayout && !isTv
                androidx.compose.runtime.LaunchedEffect(playing, bigScreenNow, testingTv) {
                    if (!playing) {
                        requestedOrientation = when {
                            testingTv -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            bigScreenNow -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                        }
                    }
                }
                // TVs and tablets get wide picture cards; phones keep tall posters unless switched in Settings.
                val bigScreen = isTv || androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
                // A phone can show the TV layout for testing (Settings, Look).
                val tvUi = isTv || AppTheme.tvLayout
                LocalIsTvSnapshot.isTv = tvUi
                CompositionLocalProvider(LocalIsTv provides tvUi, LocalWide provides (bigScreen || tvUi || vm.wideCards)) {
                    var intro by rememberSaveable { mutableStateOf(true) }
                    val screen = vm.backStack.last()

                    Box(Modifier.fillMaxSize().background(Night)) {
                        if (screen !is Screen.Player && screen !is Screen.CompatPlayer) AppBackdrop(vm.ambient ?: vm.ambientArt())
                        // Transparent so the backdrop shows through; it also sets white as the text colour.
                        Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Ink) {
                            if (intro) {
                                IntroScreen(onDone = { intro = false })
                            } else {
                                BackHandler(enabled = vm.backStack.size > 1) { vm.back() }
                                if (screen is Screen.Player) {
                                    key(screen) {
                                        PlayerScreen(
                                            screen,
                                            vm.store,
                                            vm.onlineSubtitles,
                                            vm.connection?.info?.name ?: "the phone",
                                            onExit = vm::back,
                                            onCompat = vm::switchEngine,
                                        )
                                    }
                                } else if (screen is Screen.CompatPlayer) {
                                    key(screen) { CompatPlayerScreen(screen, vm.store, onExit = vm::back, onMainPlayer = vm::switchEngine) }
                                } else {
                                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                        LinkBanner(vm, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).zIndexTop())
                                        when (screen) {
                                            Screen.Connect -> ConnectScreen(vm)
                                            Screen.Share -> ShareScreen(onBack = vm::back, onKeys = { vm.open(Screen.Keys) }, keys = vm.keys)
                                            Screen.Keys -> KeysScreen(vm)
                                            Screen.Capabilities -> CapabilitiesScreen(vm)
                                            is Screen.Properties -> key(screen) { PropertiesScreen(vm, screen) }
                                            is Screen.FixMatch -> key(screen) { FixMatchScreen(vm, screen) }
                                            is Screen.Home -> HomeScreen(vm, screen)
                                            is Screen.MovieDetail -> key(screen) { MovieDetailScreen(vm, screen) }
                                            is Screen.ShowDetail -> key(screen) { ShowDetailScreen(vm, screen) }
                                            is Screen.AlbumDetail -> key(screen) { AlbumDetailScreen(vm, screen) }
                                            is Screen.Folder -> key(screen) { FolderScreen(vm, screen) }
                                            is Screen.Player, is Screen.CompatPlayer -> Unit
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
}
