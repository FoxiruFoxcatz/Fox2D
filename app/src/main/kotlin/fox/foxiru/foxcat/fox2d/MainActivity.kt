package fox.foxiru.foxcat.fox2d

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import fox.foxiru.foxcat.fox2d.gallery.GalleryScreen
import fox.foxiru.foxcat.fox2d.gallery.ProjectEditor
import fox.foxiru.foxcat.fox2d.ui.theme.ComposeEmptyActivityTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var startup: StartupState

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState) // Hilt injects here
        splash.setKeepOnScreenCondition { !startup.ready }
        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme(dynamicColor = true) {
                val statusBarColor = MaterialTheme.colorScheme.surface
                val useDarkIcons = statusBarColor.luminance() > 0.5f
                LaunchedEffect(statusBarColor, useDarkIcons) {
                    val argb = statusBarColor.toArgb()
                    enableEdgeToEdge(
                        statusBarStyle = if (useDarkIcons) {
                            SystemBarStyle.light(argb, argb)
                        } else {
                            SystemBarStyle.dark(argb)
                        },
                    )
                }
                AppRoot()
            }
        }
    }
}

/** Gallery  ->  editor of one project;  gallery menu  ->  the old test tabs. Survives rotation / process death. */
@Composable
fun AppRoot() {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var devTools by rememberSaveable { mutableStateOf(false) }
    val id = openId
    when {
        devTools -> {
            BackHandler { devTools = false }
            DevTabs()
        }
        id != null -> ProjectEditor(id, onClose = { openId = null })
        else -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            GalleryScreen(onOpen = { openId = it }, onDevTools = { devTools = true })
        }
    }
}

/** The former tab screen minus the editor (the editor now opens from the gallery). */
@Composable
fun DevTabs() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Testing", "Settings", "Panels")

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            PrimaryTabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label) },
                    )
                }
            }

            when (tab) {
                0 -> TestingScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )

                1 -> FoxiruDemo(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )

                2 -> FoxiruPanelDemo(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
        }
    }
}
