package com.splitsmart.core.settle

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SettlerTest {
  private fun t(f: Long, t: Long, a: Long) = Transfer(f, t, a, "USD")

  // The plan is only worth showing if it moves every balance the right way, and a
  // search that finds a balanced subset without checking the amounts would pass a test
  // that only looked at the count.
  private fun assertConserves(nets: Map<Long, Long>, out: List<Transfer>) {
    val applied = mutableMapOf<Long, Long>()
    out.forEach {
      applied[it.from] = (applied[it.from] ?: 0L) - it.amountMinor
      applied[it.to] = (applied[it.to] ?: 0L) + it.amountMinor
    }
    nets.forEach { (id, net) -> assertThat(applied[id] ?: 0L).isEqualTo(net) }
  }

  @Test
  fun `empty and singleton settle to nothing`() {
    assertThat(settle(mapOf(), currencyCode = "USD")).isEmpty()
    assertThat(settle(mapOf(1L to 0L), currencyCode = "USD")).isEmpty()
    assertThat(settle(mapOf(1L to 500L, 2L to -500L), currencyCode = "USD")).containsExactly(t(2, 1, 500))
  }

  @Test
  fun `basic two person split`() {
    assertThat(settle(mapOf(1L to 5000L, 2L to -5000L), currencyCode = "USD")).containsExactly(t(2, 1, 5000))
  }

  @Test
  fun `chain compresses to single transfer`() {
    assertThat(settle(mapOf(1L to -20000L, 2L to 0L, 3L to 20000L), currencyCode = "USD")).containsExactly(t(1, 3, 20000))
  }

  @Test
  fun `cycle cancels fully`() {
    assertThat(settle(mapOf(1L to 1000L, 2L to -1000L, 3L to 0L, 4L to 0L), currencyCode = "USD"))
        .containsExactly(t(2, 1, 1000))
    assertThat(settle(mapOf(1L to 0L, 2L to 0L), currencyCode = "USD")).isEmpty()
  }

  @Test
  fun `uneven remainder nets settle exactly`() {
    val out = settle(mapOf(4L to 6666L, 5L to -3333L, 6L to -3333L), currencyCode = "USD")
    assertThat(out).containsExactly(t(5, 4, 3333), t(6, 4, 3333))
  }

  @Test
  fun `exact beats greedy on adversarial six-net case`() {
    val nets = mapOf(1L to 400L, 2L to 500L, 3L to 1200L, 4L to -900L, 5L to -700L, 6L to -500L)
    val exact = settle(nets, Force.EXACT, currencyCode = "USD")
    val greedy = settle(nets, Force.GREEDY, currencyCode = "USD")
    assertThat(exact).containsExactly(t(4, 1, 400), t(4, 2, 500), t(5, 3, 700), t(6, 3, 500))
    assertThat(greedy).hasSize(5)
    assertThat(settle(nets, currencyCode = "USD")).hasSize(4)
  }

  @Test
  fun `transfers conserve per-person nets and obey count bound`() {
    val nets = mapOf(1L to 400L, 2L to 500L, 3L to 1200L, 4L to -900L, 5L to -700L, 6L to -500L)
    val out = settle(nets, currencyCode = "USD")
    assertConserves(nets, out)
    assertThat(out.size).isEqualTo(4)
  }

  @Test
  fun `nonzero sum is rejected`() {
    assertThrows<IllegalArgumentException> { settle(mapOf(1L to 100L, 2L to -50L), currencyCode = "USD") }
  }

  @Test
  fun `lone nonzero net is rejected not swallowed`() {
    assertThrows<IllegalArgumentException> { settle(mapOf(1L to 500L), currencyCode = "USD") }
  }

  @Test
  fun `greedy tie-breaks are deterministic and sane`() {
    val out = settle(mapOf(1L to 100L, 2L to 100L, 3L to -100L, 4L to -100L), Force.GREEDY, currencyCode = "USD")
    assertThat(out).hasSize(2)
    assertThat(out.sumOf { it.amountMinor }).isEqualTo(200L)
    assertThat(settle(mapOf(1L to 100L, 2L to 100L, 3L to -100L, 4L to -100L), Force.GREEDY, currencyCode = "USD"))
        .containsExactlyElementsIn(out)
  }

  @Test
  fun `deterministic under input shuffle`() {
    val a = settle(mapOf(1L to 400L, 2L to -100L, 3L to -300L), currencyCode = "USD")
    val b = settle(mapOf(3L to -300L, 1L to 400L, 2L to -100L), currencyCode = "USD")
    assertThat(a).containsExactlyElementsIn(b)
  }

  @Test
  fun `greedy and exact reject nonzero sum directly`() {
    assertThrows<IllegalArgumentException> { greedy(mapOf(1L to 100L), currencyCode = "USD") }
    assertThrows<IllegalArgumentException> { exact(mapOf(1L to 100L, 2L to 50L), currencyCode = "USD") }
  }

  @Test
  fun `exact rejects oversized input`() {
    val big = (1L..20L).associateWith { if (it % 2 == 0L) 100L else -100L }
    assertThrows<IllegalArgumentException> { exact(big, currencyCode = "USD") }
  }

  @Test
  fun `large group uses greedy within bound`() {
    val nets = (1L..20L).associateWith { if (it % 2 == 0L) 100L else -100L }
    val out = settle(nets, currencyCode = "USD")
    // 10 balanced pairs match off one-to-one, and 10 is optimal: every transfer can
    // clear at most one of the 10 debtors, so fewer than 10 cannot settle all of
    // them. A bound of n-1 would pass even for a badly broken greedy.
    assertThat(out.size).isEqualTo(10)
    assertThat(out.sumOf { it.amountMinor }).isEqualTo(1000L)
  }

  // The exact/greedy switch is `nz.size > 15`, so 15 is the largest input the DP path
  // accepts and 16 is the smallest that must fall through to greedy.
  @Test
  fun `fifteen nets take the exact path and sixteen take greedy`() {
    assertThat(settle(balanced(15), currencyCode = "USD")).isNotEmpty()
    assertThat(settle(balanced(16), currencyCode = "USD")).isNotEmpty()
    // The 16 has to fail in exact(), which is the one that guards the bound.
    assertThrows<IllegalArgumentException> { exact(balanced(16), currencyCode = "USD") }
  }

  // A subset total can overflow Long even when every input and the full set do not:
  // MAX, -MAX, MAX, -MAX, 2, -2 sums to zero, but {MAX, MAX, 2} is 2^64. The table used
  // to wrap that to 0, so the search could pick an unbalanced subset and emit transfers
  // that do not conserve money.
  @Test
  fun `settles nets that overflow Long in intermediate subsets`() {
    val max = Long.MAX_VALUE
    val nets = mapOf(1L to max, 2L to -max, 3L to max, 4L to -max, 5L to 2L, 6L to -2L)

    assertConserves(nets, settle(nets, currencyCode = "USD"))
  }

  // n settleable members: d debtors each owing what the c creditors are owed, and c
  // creditors each owed what the d debtors owe, so the sum is exactly zero for any n. Every
  // member is nonzero, which makes this a valid probe of the 15/16 boundary.
  private fun balanced(n: Int): Map<Long, Long> {
    val debtors = n / 2
    val creditors = n - debtors
    return (1L..n.toLong()).associateWith { id ->
      if (id <= debtors) -100L * creditors else 100L * debtors
    }
  }
}
