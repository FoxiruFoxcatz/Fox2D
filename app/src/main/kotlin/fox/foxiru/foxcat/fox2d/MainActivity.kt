package fox.foxiru.foxcat.fox2d

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import fox.foxiru.foxcat.fox2d.ui.theme.ComposeEmptyActivityTheme
import fox.foxiru.foxcat.fox2d.main_canvas.EditorScreen
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
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    var tab by rememberSaveable { mutableIntStateOf(3) }
    val tabs = listOf("Testing", "Settings", "Panels", "Editor")

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
                // Index 0: FFmpeg + Oboe test area (scrolls as a normal page)
                0 -> TestingScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )

                // Scrolls as a normal page (titles, expanding items, disabled item)
                1 -> FoxiruDemo(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )

                // Bounded height so the group's own scroll works (sticky top bar + list)
                2 -> FoxiruPanelDemo(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )

                3 -> EditorScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
