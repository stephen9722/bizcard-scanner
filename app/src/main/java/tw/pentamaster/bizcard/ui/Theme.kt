package tw.pentamaster.bizcard.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette for Android 11 and below, where dynamic colour isn't available.
// Ink blue and a muted brass — a filing-cabinet palette rather than a startup one.
private val Ink = Color(0xFF1E3A4C)
private val InkLight = Color(0xFF3D6480)
private val Brass = Color(0xFF8A6A2F)
private val Paper = Color(0xFFF6F5F2)

private val LightFallback = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Brass,
    onSecondary = Color.White,
    background = Paper,
    surface = Color.White
)

private val DarkFallback = darkColorScheme(
    primary = InkLight,
    onPrimary = Color.White,
    secondary = Color(0xFFC7A55E),
    onSecondary = Color(0xFF241C08)
)

@Composable
fun BizCardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = colors, content = content)
}
