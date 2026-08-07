package com.snowzlmbot.hermes.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
  primary = Color(0xFF166534),
  onPrimary = Color.White,
  primaryContainer = Color(0xFFD7F5DD),
  secondary = Color(0xFF475569),
  secondaryContainer = Color(0xFFE2E8F0),
  tertiary = Color(0xFF9A3412),
  tertiaryContainer = Color(0xFFFFE1D5),
  background = Color(0xFFF7F8F5),
  surface = Color(0xFFFFFFFF),
  surfaceVariant = Color(0xFFECEFEA),
  error = Color(0xFFB42318),
  errorContainer = Color(0xFFFFE4E0),
)

private val DarkColors = darkColorScheme(
  primary = Color(0xFF87D39A),
  primaryContainer = Color(0xFF155E32),
  secondary = Color(0xFFCBD5E1),
  secondaryContainer = Color(0xFF334155),
  tertiary = Color(0xFFFFB59A),
  tertiaryContainer = Color(0xFF7C2D12),
  background = Color(0xFF121512),
  surface = Color(0xFF1A1F1A),
  surfaceVariant = Color(0xFF293029),
  error = Color(0xFFFFB4AB),
  errorContainer = Color(0xFF7F1D1D),
)

@Composable
internal fun HermesTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
    content = content,
  )
}
