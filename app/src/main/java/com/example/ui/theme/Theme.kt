package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
  primary = CobaltLight,
  onPrimary = Color(0xFF00243D),
  primaryContainer = CobaltDark,
  onPrimaryContainer = CobaltContainer,
  secondary = NtesOrange,
  onSecondary = Color(0xFF2B1700),
  tertiary = Color(0xFF66BB6A),
  background = DarkBackground,
  onBackground = TextPrimaryDark,
  surface = DarkSurface,
  onSurface = TextPrimaryDark,
  surfaceVariant = DarkSurfaceVariant,
  onSurfaceVariant = TextSecondaryDark,
  outline = DarkBorder,
  error = Color(0xFFEF5350),
  onError = Color.White,
  errorContainer = Color(0xFF5C1515),
  onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
  primary = CobaltPrimary,
  onPrimary = Color.White,
  primaryContainer = CobaltContainer,
  onPrimaryContainer = OnCobaltContainer,
  secondary = NtesOrange,
  onSecondary = Color.White,
  tertiary = VerifiedEmerald,
  background = SlateBackground,
  onBackground = TextPrimaryLight,
  surface = SurfaceWhite,
  onSurface = TextPrimaryLight,
  surfaceVariant = CardSurfaceLight,
  onSurfaceVariant = TextSecondaryLight,
  outline = BorderLight,
  error = StopCrimson,
  onError = Color.White,
  errorContainer = StopCrimsonContainer,
  onErrorContainer = Color(0xFF7F0000),
)

@Composable
fun SmartTrainTrackerTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content,
  )
}
