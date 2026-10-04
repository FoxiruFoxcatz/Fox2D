package fox.foxiru.foxcat.fox2d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun FoxiruPanelDemo(modifier: Modifier = Modifier) {
    var selected by remember { mutableIntStateOf(0) }
    val layouts = remember {
        listOf("default" to "2.0") + (1..12).map { "layout $it" to "1.$it" } // extra rows to test scrolling
    }

    FoxiruTwoPane(
        modifier = modifier,
        first = { paneModifier ->
            // Sticky top bar + scrollable list
            FoxiruGroup(
                modifier = paneModifier,
                topBarColor = MaterialTheme.colorScheme.surfaceContainerLow,
                topBar = {
                    FoxiruTopBarAction("Refresh", Icons.Filled.Refresh, onClick = { })
                    FoxiruTopBarAction("Import", Icons.Filled.Add, onClick = { })
                    FoxiruTopBarAction("Create", Icons.Filled.AddBox, onClick = { })
                },
                scrollable = true,
                segmented = false,
            ) {
                layouts.forEachIndexed { i, (name, version) ->
                    item {
                        FoxiruItem(
                            title = name,
                            subtitle = version,
                            leading = { enabled ->
                                RadioButton(selected = selected == i, onClick = null, enabled = enabled)
                            },
                            onClick = { selected = i },
                            trailing = { enabled ->
                                Row {
                                    IconButton(onClick = { }, enabled = enabled) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
                                    }
                                    IconButton(onClick = { }, enabled = enabled) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
        second = { paneModifier ->
            // Details panel (scrolls only if it doesn't fit)
            FoxiruGroup(
                modifier = paneModifier,
                panel = true,
                scrollable = true,
                segmented = false,
            ) {
                listOf(
                    "Layout Name" to "default",
                    "Layout Author" to "Foxiru Foxcat",
                    "Layout Version" to "2.0",
                    "Layout Description" to "I have no description yet.",
                ).forEach { (label, value) ->
                    item {
                        FoxiruItem(
                            title = label,
                            trailing = {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        Button(onClick = { }) {
                            Text("Share", maxLines = 1, softWrap = false)
                        }
                        Button(onClick = { }) {
                            Text("Edit Layout", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        },
    )
}

@Preview(name = "Tablet / landscape", showBackground = true, widthDp = 900, heightDp = 420)
@Composable
private fun FoxiruPanelDemoWidePreview() {
    FoxiruPanelDemo(modifier = Modifier.fillMaxSize().padding(16.dp))
}

@Preview(name = "Phone portrait", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun FoxiruPanelDemoPhonePreview() {
    FoxiruPanelDemo(modifier = Modifier.fillMaxSize().padding(16.dp))
}