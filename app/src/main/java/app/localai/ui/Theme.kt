package app.localai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Emerald = Color(0xFF0F766E)
private val Light = lightColorScheme(
    primary = Emerald, onPrimary = Color.White,
    primaryContainer = Color(0xFFCCEFEA), onPrimaryContainer = Color(0xFF00201D),
    secondaryContainer = Color(0xFFE3E8E7), background = Color(0xFFF8FAFA), surface = Color(0xFFF8FAFA),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF5CD8C9), onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFFCCEFEA),
    secondaryContainer = Color(0xFF2A3331), background = Color(0xFF101414), surface = Color(0xFF101414),
)

@Composable
fun LocalAITheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
