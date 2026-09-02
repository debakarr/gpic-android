package com.gpic.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4285F4),
    secondary = Color(0xFF34A853),
    tertiary = Color(0xFFFBBC05),
    background = Color(0xFF0F1419),
    surface = Color(0xFF1A2128),
    surfaceVariant = Color(0xFF2A323A),
    onPrimary = Color.White,
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    secondary = Color(0xFF137333),
    tertiary = Color(0xFFF9AB00),
    background = Color(0xFFF8F9FA),
    surface = Color.White,
)

@Composable
fun GpicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = scheme, content = content)
}
