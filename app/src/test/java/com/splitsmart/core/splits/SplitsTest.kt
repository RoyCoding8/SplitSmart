package com.splitsmart.core.splits

import com.google.common.truth.Truth.assertThat
import com.splitsmart.core.settle.greedy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SplitsTest {
  @Test
  fun `equal splits remainder to first in order`() {
    assertThat(equal(10000L, listOf(1L, 2L, 3L)))
        .containsExactlyEntriesIn(mapOf(1L to 3334L, 2L to 3333L, 3L to 3333L))
    assertThat(equal(100L, listOf(1L, 2L)).values.sum()).isEqualTo(100L)
  }

  @Test
  fun `exact requires sum match`() {
    assertThat(exact(10000L, mapOf(1L to 6000L, 2L to 4000L)))
        .containsExactlyEntriesIn(mapOf(1L to 6000L, 2L to 4000L))
    assertThrows<IllegalArgumentException> { exact(10000L, mapOf(1L to 6000L, 2L to 3000L)) }
    assertThrows<IllegalArgumentException> { exact(10000L, mapOf(1L to -100L, 2L to 10100L)) }
  }

  @Test
  fun `exact rejects wrapping sums`() {
    assertThrows<IllegalArgumentException> {
      exact(-2L, mapOf(1L to Long.MAX_VALUE, 2L to Long.MAX_VALUE))
    }
    assertThrows<IllegalArgumentException> {
      shares(100L, mapOf(1L to Long.MAX_VALUE, 2L to Long.MAX_VALUE))
    }
    assertThrows<IllegalArgumentException> {
      greedy(mapOf(1L to Long.MAX_VALUE, 2L to Long.MAX_VALUE, 3L to 2L), "USD")
    }
  }

  @Test
  fun `percent uses bps with largest remainder`() {
    assertThat(percent(10000L, mapOf(1L to 3334, 2L to 3333, 3L to 3333)))
        .containsExactlyEntriesIn(mapOf(1L to 3334L, 2L to 3333L, 3L to 3333L))
    assertThrows<IllegalArgumentException> { percent(10000L, mapOf(1L to 5000, 2L to 4000)) }
    assertThat(percent(100L, mapOf(1L to 3333, 2L to 3333, 3L to 3334)).values.sum())
        .isEqualTo(100L)
  }

  @Test
  fun `shares weight proportionally with remainder`() {
    assertThat(shares(10000L, mapOf(1L to 2L, 2L to 1L, 3L to 1L)))
        .containsExactlyEntriesIn(mapOf(1L to 5000L, 2L to 2500L, 3L to 2500L))
    assertThat(shares(100L, mapOf(1L to 1L, 2L to 1L, 3L to 1L)).values.sum()).isEqualTo(100L)
    // A negative weight is still refused: owed only ever moves toward a member.
    assertThrows<IllegalArgumentException> { shares(100L, mapOf(1L to -1L, 2L to 2L)) }
  }

  @Test
  fun `a zero weight is a member owing nothing`() {
    // largestRemainder has always accepted a zero weight. shares() refused one,
    // which made a state the ledger can store -- shares() floors every member,
    // so a 2-cent bill split 1:2:3 stores {0, 1, 1} -- unrepresentable as an
    // input, and the editor could not reopen such an expense.
    assertThat(shares(2L, mapOf(1L to 0L, 2L to 1L, 3L to 1L)))
        .containsExactlyEntriesIn(mapOf(1L to 0L, 2L to 1L, 3L to 1L))
    assertThat(shares(100L, mapOf(1L to 0L, 2L to 1L)).values.sum()).isEqualTo(100L)
  }

  @Test
  fun `equal rejects duplicate ids`() {
    assertThrows<IllegalArgumentException> { equal(100L, listOf(1L, 1L)) }
  }

  @Test
  fun `largest remainder ties break by id regardless of input order`() {
    val shuffled = percent(5L, mapOf(2L to 3333, 1L to 3333, 3L to 3334))
    assertThat(shuffled).containsExactlyEntriesIn(mapOf(1L to 2L, 2L to 1L, 3L to 2L))
  }

  @Test
  fun `largestRemainder rejects empty weights and bad denom`() {
    assertThrows<IllegalArgumentException> { largestRemainder(100L, emptyMap(), 1L) }
    assertThrows<IllegalArgumentException> { largestRemainder(10L, mapOf(1L to 100L), 10L) }
    assertThrows<IllegalArgumentException> {
      largestRemainder(100L, mapOf(1L to -5L, 2L to 105L), 100L)
    }
  }

  @Test
  fun `split overflow is rejected`() {
    assertThrows<IllegalArgumentException> { shares(Long.MAX_VALUE, mapOf(1L to 2L, 2L to 1L)) }
  }

  @Test
  fun `single participant takes all`() {
    assertThat(equal(999L, listOf(7L))).containsExactlyEntriesIn(mapOf(7L to 999L))
  }

  @Test
  fun `largest remainder lands on largest fractions in order`() {
    assertThat(percent(100L, mapOf(1L to 5000, 2L to 3000, 3L to 2000)))
        .containsExactlyEntriesIn(mapOf(1L to 50L, 2L to 30L, 3L to 20L))
    assertThat(shares(10L, mapOf(1L to 1L, 2L to 1L, 3L to 1L, 4L to 1L, 5L to 1L)).values.sum())
        .isEqualTo(10L)
    assertThat(shares(7L, mapOf(1L to 1L, 2L to 1L, 3L to 1L)))
        .containsExactlyEntriesIn(mapOf(1L to 3L, 2L to 2L, 3L to 2L))
    assertThat(percent(5L, mapOf(1L to 3333, 2L to 3333, 3L to 3334)))
        .containsExactlyEntriesIn(mapOf(1L to 2L, 2L to 1L, 3L to 2L))
  }
}
