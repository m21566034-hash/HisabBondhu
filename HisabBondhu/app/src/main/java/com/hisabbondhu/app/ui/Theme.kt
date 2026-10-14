package com.hisabbondhu.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(primary = Color(0xFF0B6E4F), onPrimary = Color.White, secondary = Color(0xFF3D6B5B),
    background = Color(0xFFF7F9F8), surface = Color(0xFFF7F9F8), tertiary = Color(0xFFE65100))
private val Dark = darkColorScheme(primary = Color(0xFF5FD3A8), onPrimary = Color(0xFF00382A), secondary = Color(0xFF9CCBB9),
    background = Color(0xFF0F1512), surface = Color(0xFF0F1512), tertiary = Color(0xFFFFB380))

@Composable
fun HisabTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
