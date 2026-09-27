package com.splitsmart.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun SplitSmartTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit
) {
  val scheme =
      when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
          val ctx = LocalContext.current
          if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
      }
  MaterialTheme(colorScheme = scheme, content = content)
}
