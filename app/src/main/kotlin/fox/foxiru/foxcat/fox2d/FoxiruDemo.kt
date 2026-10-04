package fox.foxiru.foxcat.fox2d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun FoxiruDemo(modifier: Modifier = Modifier) {
    var chemicals by remember { mutableStateOf(true) }
    var graphicsApi by remember { mutableIntStateOf(2) }
    var scale by remember { mutableFloatStateOf(1f) }
    var checkA by remember { mutableStateOf(true) }
    var checkB by remember { mutableStateOf(false) }
    var nativeReport by remember { mutableStateOf<String?>(null) }
    val runNative: () -> Unit = {
        nativeReport = try {
            NativeLib.mathTest()
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    val apiOptions = listOf("List 1", "List 2", "Launcher Fox", "Vulkan")

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Group with big title + big icon
        FoxiruGroup(title = "Set Edit", icon = Icons.Filled.Settings) {
            item {
                FoxiruItem(
                    title = "Chemicals",
                    subtitle = "I Put Chemicals and Kill Them.\n~Foxiru Foxcat",
                    icon = Icons.Filled.Science,
                    onClick = { chemicals = !chemicals },
                    trailing = { enabled ->
                        Switch(checked = chemicals, onCheckedChange = null, enabled = enabled)
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "Change Something",
                    subtitle = "Set something else to make",
                    value = "Selected: ${apiOptions[graphicsApi]}",
                    trailing = { enabled ->
                        IconButton(onClick = { }, enabled = enabled) {
                            Icon(Icons.Filled.Download, contentDescription = "Download")
                        }
                    },
                    expandedContent = { enabled ->
                        apiOptions.forEachIndexed { i, label ->
                            FoxiruRadioOption(
                                label = label,
                                selected = graphicsApi == i,
                                onSelect = { graphicsApi = i },
                                enabled = enabled,
                            )
                        }
                    },
                )
            }
            item {
                FoxiruItem(
                    title = " Larper Scale",
                    subtitle = "Lower is your not a Larper, Higher that u are a Larper now.",
                    content = { enabled ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Slider(
                                value = scale,
                                onValueChange = { scale = it },
                                valueRange = 0.25f..1f,
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ) {
                                Text(
                                    text = "${(scale * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    },
                )
            }
        }

        // Group with title only (no icon)
        FoxiruGroup(title = "Advanced") {
            item {
                FoxiruItem(
                    title = "Extra options",
                    subtitle = "Checkers and buttons inside the expanded area",
                    icon = Icons.Filled.NetworkCell,
                    expandedContent = { enabled ->
                        FoxiruCheckOption("Option A", checkA, { checkA = it }, enabled)
                        FoxiruCheckOption("Option B", checkB, { checkB = it }, enabled)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            OutlinedButton(onClick = { }, enabled = enabled) { Text("Reset") }
                            Button(onClick = { }, enabled = enabled) { Text("Apply") }
                        }
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "The MF Ass OFF",
                    subtitle = "Not supported on this device",
                    enabled = false,
                    trailing = { enabled ->
                        Switch(checked = false, onCheckedChange = null, enabled = enabled)
                    },
                    expandedContent = { enabled ->
                        FoxiruRadioOption("Ass", true, { }, enabled)
                    },
                )
            }
        }

        FoxiruGroup(title = "Native Math", icon = Icons.Filled.Functions) {
            item {
                FoxiruItem(
                    title = "Run Eigen + GLM test",
                    subtitle = "Calls the native fox2d library through JNI",
                    value = nativeReport?.lineSequence()
                        ?.lastOrNull { it.startsWith("RESULT") || it.startsWith("ERROR") },
                    icon = Icons.Filled.Science,
                    onClick = runNative,
                    trailing = { enabled ->
                        IconButton(onClick = runNative, enabled = enabled) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Run")
                        }
                    },
                )
            }
            val lines = nativeReport?.lines()?.filter { it.isNotBlank() }.orEmpty()
            if (lines.isNotEmpty()) {
                item {
                    FoxiruItem(
                        title = "Results",
                        content = { _ ->
                            lines.forEach { line ->
                                val color = when {
                                    line.contains("FAIL") || line.startsWith("ERROR") ->
                                        MaterialTheme.colorScheme.error
                                    line.endsWith("OK") || line.contains("ALL PASSED") ->
                                        MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Text(
                                    text = line,
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

object NativeLib {
    init {
        System.loadLibrary(BuildConfig.NATIVE_LIB_NAME)
    }

    external fun mathTest(): String
}

@Preview(showBackground = true)
@Composable
private fun FoxiruDemoPreview() {
    FoxiruDemo(modifier = Modifier.padding(16.dp))
}