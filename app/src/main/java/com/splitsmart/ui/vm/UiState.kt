package com.splitsmart.ui.vm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

sealed interface Load<out T> {
  data object Busy : Load<Nothing>

  data class Ok<T>(val v: T) : Load<T>

  data class Err(val m: String) : Load<Nothing>
}

/** Per-group accent drawn only from theme roles (dynamic-color safe, no hardcoded hex). */
@Composable
fun groupContainer(idx: Int): Color =
    when (idx.coerceIn(0, 4)) {
      1 -> MaterialTheme.colorScheme.primaryContainer
      2 -> MaterialTheme.colorScheme.tertiaryContainer
      3 -> MaterialTheme.colorScheme.errorContainer
      4 -> MaterialTheme.colorScheme.surfaceVariant
      else -> MaterialTheme.colorScheme.secondaryContainer
    }

/**
 * A signed amount, signed the way a statement is: the currency code, then the
 * sign. The sign must not lead the code, or every negative balance in the app
 * reads "-USD 12.50" where the same amount anywhere else reads "USD -12.50".
 */
fun money(minor: Long, code: String): String {
  val neg = minor < 0
  val q = minor / 100
  val r = kotlin.math.abs(minor % 100)
  return code + " " + (if (neg) "-" else "") + (if (neg) -q else q) + "." +
      r.toString().padStart(2, '0')
}
