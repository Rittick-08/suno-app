package com.example.ui.theme

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

private val DarkColorScheme =
  darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DarkAppBg,
    onBackground = DarkTextLight,
    surface = DarkSurface,
    onSurface = DarkTextLight,
    surfaceVariant = DarkCardBg,
    onSurfaceVariant = DarkTextMuted,
    outline = DarkOutline
  )

private val LightColorScheme =
  lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = PillBg,
    onPrimaryContainer = DarkPurple,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = AppBg,
    onBackground = TextDark,
    surface = AppBg,
    onSurface = TextDark,
    surfaceVariant = CardBg,
    onSurfaceVariant = TextMuted,
    outline = OutlineColor
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disabling dynamic colors by default so the hand-designed brand theme is perfectly preserved
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
