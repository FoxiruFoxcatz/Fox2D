package fox.foxiru.foxcat.fox2d.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val OrangeDarkColorScheme = darkColorScheme(
    primary = Orange80,
    onPrimary = Orange20,

    primaryContainer = Orange30,
    onPrimaryContainer = Orange90,

    secondary = OrangeGrey80,
    onSecondary = OrangeGrey20,

    secondaryContainer = OrangeGrey30,
    onSecondaryContainer = OrangeGrey90,

    tertiary = DeepOrange80,
    onTertiary = DeepOrange20,

    tertiaryContainer = DeepOrange30,
    onTertiaryContainer = DeepOrange90
)

private val OrangeLightColorScheme = lightColorScheme(
    primary = Orange40,
    onPrimary = Orange100,

    primaryContainer = Orange90,
    onPrimaryContainer = Orange10,

    secondary = OrangeGrey40,
    onSecondary = OrangeGrey100,

    secondaryContainer = OrangeGrey90,
    onSecondaryContainer = OrangeGrey10,

    tertiary = DeepOrange40,
    onTertiary = DeepOrange100,

    tertiaryContainer = DeepOrange90,
    onTertiaryContainer = DeepOrange10
)

@Composable
fun ComposeEmptyActivityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current

            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }

        darkTheme -> OrangeDarkColorScheme

        else -> OrangeLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}