package com.pojia.compositor.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val editorColors = darkColorScheme(
    primary = Color(0xFFE4E4E4), onPrimary = Color(0xFF232323),
    primaryContainer = Color(0xFF4A4A4A), onPrimaryContainer = Color.White,
    secondary = Color(0xFFC8C8C8), onSecondary = Color(0xFF242424),
    secondaryContainer = Color(0xFF464646), onSecondaryContainer = Color.White,
    tertiary = Color(0xFFD0D0D0), onTertiary = Color(0xFF252525),
    background = Color(0xFF242424), onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF2B2B2B), onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFC1C1C1),
    outline = Color(0xFF585858), outlineVariant = Color(0xFF3D3D3D),
)

@Composable
internal fun CompositorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = editorColors, content = content)
}
