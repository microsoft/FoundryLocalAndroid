package com.microsoft.foundrylocal.lab.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LabColors = darkColorScheme(
    primary = Color(0xFF67E8F9),
    onPrimary = Color(0xFF00242C),
    primaryContainer = Color(0xFF123743),
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondary = Color(0xFFA5B4FC),
    background = Color(0xFF06141B),
    onBackground = Color(0xFFE6F6FA),
    surface = Color(0xFF0B1F28),
    onSurface = Color(0xFFE6F6FA),
    surfaceVariant = Color(0xFF12303B),
    onSurfaceVariant = Color(0xFFAFC8D0),
    outline = Color(0xFF31515D),
    error = Color(0xFFFF8A8A)
)

@Composable
fun FoundryLocalLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LabColors,
        typography = Typography(),
        content = content
    )
}
