package dev.bit.media

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF186B59), onPrimary = Color.White,
    primaryContainer = Color(0xFFAFF2D9), onPrimaryContainer = Color(0xFF00382D),
    secondaryContainer = Color(0xFFD3E8DD), onSecondaryContainer = Color(0xFF18372E),
    tertiary = Color(0xFF405F89), surface = Color(0xFFF8FAF7),
    surfaceContainerLow = Color(0xFFF0F5F0), surfaceContainerHigh = Color(0xFFE4EBE6),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF93D6BF), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF00513F), onPrimaryContainer = Color(0xFFAFF2D9),
    secondaryContainer = Color(0xFF354B43), onSecondaryContainer = Color(0xFFD3E8DD),
    tertiary = Color(0xFFACC8F4), surface = Color(0xFF101411),
    surfaceContainerLow = Color(0xFF191E1B), surfaceContainerHigh = Color(0xFF262D29),
)

@Composable
internal fun StudioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}

internal enum class Destination { Extractor, Converter }

private fun symbol(name: String, path: String) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
    }.build()

private val Extract = symbol("Extract", "M11,3h2v9.17l3.59,-3.58L18,10l-6,6l-6,-6l1.41,-1.41L11,12.17z M4,16h2v3h12v-3h2v5H4z")
internal val AudioSymbol = symbol("Audio", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
internal val VideoSymbol = symbol("Video", "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z")
internal val EditSymbol = symbol("Edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
internal val CloseSymbol = symbol("Close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
private val Convert = symbol("Convert", "M4,6h12V3l5,4l-5,4V8H4z M20,18H8v3l-5,-4l5,-4v3h12z")

@Composable
internal fun DestinationIcon(destination: Destination) {
    Icon(if (destination == Destination.Extractor) Extract else Convert, contentDescription = null)
}
