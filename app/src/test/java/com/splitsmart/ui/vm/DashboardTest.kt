package com.splitsmart.ui.vm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DashboardTest {
  @Test
  fun `empty in empty out`() {
    assertThat(summarizeDashboard(emptyList())).isEmpty()
    assertThat(summarizeDashboard(listOf("USD" to 0L))).isEmpty()
  }

  @Test
  fun `splits owed and owe per currency`() {
    val out = summarizeDashboard(listOf("USD" to 500L, "USD" to -200L, "EUR" to -100L))
    assertThat(out)
        .containsExactly(
            DashRow("EUR", 0L, 100L),
            DashRow("USD", 500L, 200L),
        )
        .inOrder()
  }

  @Test
  fun `settled groups contribute nothing`() {
    val out = summarizeDashboard(listOf("USD" to 0L, "USD" to 300L))
    assertThat(out).containsExactly(DashRow("USD", 300L, 0L))
  }

  // A row carrying a currency no rate can convert has to say which, or the dashboard states
  // a parity figure as a balance the user is expected to trust.
  @Test
  fun `a row names the currencies it could not convert`() {
    val out = summarizeDashboard(listOf("USD" to 500L), mapOf("USD" to listOf("EUR")))
    assertThat(out.single().unpriced).containsExactly("EUR")
  }

  // The row is the sum of every group in that currency, so one unconverted group behind it is
  // enough: the union is what makes the warning match the total.
  @Test
  fun `unpriced currencies from several groups behind one row are all named`() {
    val out =
        summarizeDashboard(
            listOf("USD" to 100L), mapOf("USD" to listOf("EUR", "JPY", "EUR")))
    assertThat(out.single().unpriced).containsExactly("EUR", "JPY").inOrder()
  }

  /** Nothing unconvertible means the card reads exactly as it always did. */
  @Test
  fun `a convertible currency carries no note`() {
    assertThat(summarizeDashboard(listOf("USD" to 500L)).single().unpriced).isEmpty()
    assertThat(summarizeDashboard(listOf("USD" to 500L), mapOf("GBP" to listOf("EUR"))).single().unpriced)
        .isEmpty()
  }
}
