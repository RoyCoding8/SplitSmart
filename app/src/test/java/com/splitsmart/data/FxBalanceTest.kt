package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Converting a split expense row by row loses the property that the rows sum to the
// total, and every balance in this app is built out of those rows. The nets must sum to
// zero, and the group must stay settleable.
@RunWith(RobolectricTestRunner::class)
class FxBalanceTest : DbTest() {

  // 1000 minor units at rate 1.1 converts to 1100, but 334/333/333 each convert and
  // round on their own to 367/366/366 = 1099. The one unit of drift used to leave the
  // nets summing to +1, which `settle` rejects, and since a settlement only ever adds
  // +x/-x, nothing the user did could fix it.
  @Test
  fun `an fx split still sums to zero and stays settleable`() = runTest {
    val (g, ids) = MoneyTest.members(repo, now, listOf("A", "B", "C"))
    val (a, b, c) = Triple(ids[0], ids[1], ids[2])
    MoneyTest.bill(repo, now, g, a, ids, 1000L, "EUR")
    MoneyTest.rate(repo, g, now, "EUR", "USD", 1.1)

    val nets = repo.nets(g).first()
    assertThat(nets.values.sum()).isEqualTo(0L)
    // Largest remainder hands the leftover unit to whoever it belongs to. Rounding each
    // share on its own gives 367/366/366 = 1099, so the single unit of slack goes to the
    // largest remainder, here a's 0.4: a owes 368 and its net is 732, not the 733 a naive
    // reading of the 1100 total gives.
    assertThat(nets[a]).isEqualTo(732L)
    assertThat(nets[b]).isEqualTo(-366L)
    assertThat(nets[c]).isEqualTo(-366L)
    // The plan moves the creditor's net, not the whole expense, so it totals 732.
    val plan = repo.plan(g, true)
    assertThat(plan).isNotEmpty()
    assertThat(plan.sumOf { it.amountMinor }).isEqualTo(732L)
    assertThat(plan.map { it.from }.toSet()).containsExactly(b, c)
    assertThat(plan.map { it.to }.toSet()).containsExactly(a)
  }

  // A split across two payers has to conserve on both sides at once, which is the harder
  // case: the fronting and the owing are converted by separate apportionments, so an
  // error in either shows up here.
  @Test
  fun `a multi-payer fx expense conserves on both sides`() = runTest {
    val (g, ids) = MoneyTest.members(repo, now, listOf("A", "B", "C"))
    // A fronts half and B fronts half, and the three still owe a third each.
    MoneyTest.bill(
        repo, now, g, ids[0], ids, 1000L, "EUR", payments = mapOf(ids[0] to 500L, ids[1] to 500L))
    MoneyTest.rate(repo, g, now, "EUR", "USD", 1.1)

    assertThat(repo.nets(g).first().values.sum()).isEqualTo(0L)
    assertThat(repo.plan(g, true)).isNotEmpty()
  }

  // A sweep, because a residual that depends on the particular denominators involved
  // is only caught by varying them.
  @Test
  fun `nets sum to zero across a range of rates and split sizes`() = runTest {
    val rates = listOf(0.5, 0.75, 1.1, 1.3, 1.5, 2.0, 3.7, 0.3333, 7.13)
    for ((idx, rate) in rates.withIndex()) {
      for (n in 2..7) {
        val (g, ids) =
            MoneyTest.members(repo, now, (0 until n).map { "M$it" }, name = "T$n$idx")
        // A prime-ish, non-round amount so the split is genuinely uneven.
        MoneyTest.bill(repo, now, g, ids.first(), ids, 1000L + idx * 37L, "EUR")
        MoneyTest.rate(repo, g, now, "EUR", "USD", rate)

        val nets = repo.nets(g).first()
        assertThat(nets.values.sum()).isEqualTo(0L)
        assertThat(repo.plan(g, true)).isNotEmpty()
      }
    }
  }

  // Each expense has to contribute zero on its own, or their residuals accumulate into a
  // group that can no longer be settled.
  @Test
  fun `residuals do not accumulate across expenses`() = runTest {
    val (g, ids) = MoneyTest.members(repo, now, listOf("A", "B", "C"))
    val (a, b) = ids[0] to ids[1]
    listOf(1000L, 777L, 1234L, 55L, 9_999L).forEach { amt ->
      MoneyTest.bill(repo, now, g, a, ids, amt, "EUR")
    }
    MoneyTest.rate(repo, g, now, "EUR", "USD", 1.1)
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 111L, dateEpoch = now))

    val nets = repo.nets(g).first()
    assertThat(nets.values.sum()).isEqualTo(0L)
    // Settling the whole plan must zero the group, which is only true if the individual
    // contributions were already zero.
    assertThat(repo.settleAll(g)).isAtLeast(1)
    assertThat(repo.nets(g).first().values.sum()).isEqualTo(0L)
  }

  /** A rate of 1.0 is the common case and must stay exact, not merely balanced. */
  @Test
  fun `no conversion needed stays exact`() = runTest {
    val (g, ids) = MoneyTest.members(repo, now, listOf("A", "B"))
    MoneyTest.bill(repo, now, g, ids[0], ids, 1000L, "USD")

    assertThat(repo.nets(g).first())
        .containsExactlyEntriesIn(mapOf(ids[0] to 500L, ids[1] to -500L))
  }
}
