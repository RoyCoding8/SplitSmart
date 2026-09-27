package com.splitsmart.core.splits

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ItemizedTest {
  @Test
  fun `proportional tip splits by subtotal share`() {
    val out = itemized(mapOf(1L to 6000L, 2L to 4000L), 10000L, 1500L, 500L, TipMode.PROPORTIONAL)
    assertThat(out.values.sum()).isEqualTo(12000L)
    assertThat(out).containsExactlyEntriesIn(mapOf(1L to 7200L, 2L to 4800L))
  }

  @Test
  fun `equal tip splits surcharge evenly`() {
    val out = itemized(mapOf(1L to 6000L, 2L to 4000L), 10000L, 1000L, 0L, TipMode.EQUAL)
    assertThat(out.values.sum()).isEqualTo(11000L)
    assertThat(out).containsExactlyEntriesIn(mapOf(1L to 6500L, 2L to 4500L))
  }

  @Test
  fun `rounding dust is conserved and deterministic`() {
    val a = itemized(mapOf(1L to 3333L, 2L to 3333L, 3L to 3334L), 10000L, 100L, 0L, TipMode.EQUAL)
    assertThat(a.values.sum()).isEqualTo(10100L)
    assertThat(a.values.max()!! - a.values.min()!!).isAtMost(1L)
    assertThat(
            itemized(mapOf(1L to 3333L, 2L to 3333L, 3L to 3334L), 10000L, 100L, 0L, TipMode.EQUAL))
        .isEqualTo(a)
  }

  @Test
  fun `zero surcharge returns subtotals`() {
    assertThat(itemized(mapOf(1L to 100L, 2L to 200L), 300L, 0L, 0L, TipMode.PROPORTIONAL))
        .containsExactlyEntriesIn(mapOf(1L to 100L, 2L to 200L))
  }

  @Test
  fun `proportional tip on zero subtotals falls back to even split`() {
    val out = itemized(mapOf(1L to 0L, 2L to 0L), 0L, 100L, 0L, TipMode.PROPORTIONAL)
    assertThat(out).containsExactlyEntriesIn(mapOf(1L to 50L, 2L to 50L))
  }

  @Test
  fun `subtotals must sum to total`() {
    assertThrows<IllegalArgumentException> {
      itemized(mapOf(1L to 100L), 200L, 0L, 0L, TipMode.EQUAL)
    }
  }
}
