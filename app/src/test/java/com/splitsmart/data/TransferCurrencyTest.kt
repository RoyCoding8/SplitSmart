package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import com.splitsmart.ui.vm.settlementAmountIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The unsimplified plan is built per expense, so each of its rows is an amount in *that
// expense's* currency. `Transfer` carries that code, so the settle-up screen never has to
// format a foreign row in the group's own.
@RunWith(RobolectricTestRunner::class)
class TransferCurrencyTest : DbTest() {

  /** A and B in a USD group, with an [eur] EUR bill and a [usd] USD one for them to owe on. */
  private suspend fun pairWithBills(eur: Long?, usd: Long?): Pair<Long, List<Long>> {
    val (gid, ids) = MoneyTest.members(repo, now, listOf("A", "B"))
    eur?.let { MoneyTest.bill(repo, now, gid, ids[0], ids, it, "EUR") }
    usd?.let { MoneyTest.bill(repo, now, gid, ids[0], ids, it, "USD") }
    return gid to ids
  }

  // The row has to say which currency it is in, or the screen that renders it has to
  // guess: one expense in EUR and a member who owes on it is a EUR row.
  @Test
  fun `an unsimplified plan row names the currency of the expense it came from`() = runTest {
    val (gid, ids) = pairWithBills(eur = 1000L, usd = null)

    val plan = repo.plan(gid, false)
    assertThat(plan).hasSize(1)
    assertThat(plan.single().from).isEqualTo(ids[1])
    assertThat(plan.single().to).isEqualTo(ids[0])
    assertThat(plan.single().amountMinor).isEqualTo(500L)
    assertThat(plan.single().currencyCode).isEqualTo("EUR")
  }

  // Aggregation by pair is what collapsed two currencies into one number: summing them
  // would give a single 1500 row in an undefined unit.
  @Test
  fun `a pair owing in two currencies stays two rows`() = runTest {
    val (gid, _) = pairWithBills(eur = 1000L, usd = 2000L)

    val plan = repo.plan(gid, false)
    assertThat(plan).hasSize(2)
    assertThat(plan.map { it.amountMinor to it.currencyCode })
        .containsExactly(1000L to "USD", 500L to "EUR")
  }

  // The simplified plan is derived from the group-wide nets, which are already in the
  // group's currency. It has to keep saying so rather than leaving the field blank for a
  // caller to infer.
  @Test
  fun `a simplified plan row is in the group currency`() = runTest {
    val (gid, _) = pairWithBills(eur = null, usd = 1000L)

    val plan = repo.plan(gid, true)
    assertThat(plan).hasSize(1)
    assertThat(plan.single().currencyCode).isEqualTo("USD")
  }

  // A row carried through unconverted would clear `recordSettlement`'s bound check (which
  // is against a net computed in the group currency) and record 5 USD against a 5.50 USD
  // debt, leaving the balance still owed.
  @Test
  fun `both rows restate into a settlement the ledger accepts`() = runTest {
    val (gid, ids) = pairWithBills(eur = 1000L, usd = 2000L)
    MoneyTest.rate(repo, gid, now, "EUR", "USD", 1.1)

    val rates = repo.rates(gid).first()
    val owed = repo.nets(gid).first()[ids[1]]?.let { -it }!!
    // The unsimplified plan is per expense, so each row is that expense's share:
    // 500 EUR -> 5.50 USD and 1000 USD -> 10.00 USD, and the two together are the
    // 15.50 USD the ledger says B owes.
    assertThat(repo.plan(gid, false).map { settlementAmountIn(it, "USD", rates, now) })
        .containsExactly(550L, 1000L)
        .inOrder()
    assertThat(repo.plan(gid, false).sumOf { settlementAmountIn(it, "USD", rates, now) })
        .isEqualTo(owed)

    repo.plan(gid, false).forEach {
      repo.recordSettlement(
          Settlement(
              groupId = gid,
              fromId = it.from,
              toId = it.to,
              amountMinor = settlementAmountIn(it, "USD", rates, now),
              dateEpoch = now))
    }
    assertThat(repo.nets(gid).first()[ids[1]]).isEqualTo(0L)
  }
}
