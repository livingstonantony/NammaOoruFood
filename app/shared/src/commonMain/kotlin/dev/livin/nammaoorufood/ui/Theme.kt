package dev.livin.nammaoorufood.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EBD8),
    onPrimaryContainer = Color(0xFF0B2E0E),
    secondary = Color(0xFFE67E22),
    onSecondary = Color.White,
    background = Color(0xFFFFFBF5),
    surface = Color(0xFFFFFBF5),
    onBackground = Color(0xFF1C1B17),
    onSurface = Color(0xFF1C1B17),
    error = Color(0xFFB3261E),
)

@Composable
fun NammaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)
