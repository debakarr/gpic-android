package com.gpic.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
    // Material You wallpaper colors on Android 12+; the Google palette above elsewhere.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
