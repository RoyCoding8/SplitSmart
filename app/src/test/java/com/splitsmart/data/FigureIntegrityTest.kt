package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FigureIntegrityTest : DbTest() {

  // Simplification nets each member once and makes at most n-1 payments; the unsimplified
  // plan is built per expense and repeats a pair across separate meals, so the two plans
  // diverge in size even though they are built from the same nets.
  @Test
  fun `the settle all count describes the rows it records`() = runTest {
    val (g, ids) = MoneyTest.members(repo, now, listOf("A", "B", "C", "D"))

    suspend fun bill(payer: Long, amountMinor: Long, at: Long) =
        MoneyTest.bill(repo, now, g, payer, ids, amountMinor, "USD", atEpoch = now + at)

    bill(ids[0], 6000, 0)
    bill(ids[1], 1000, 1)

    val simplified = repo.plan(g, true)
    val listed = repo.plan(g, false)
    assertThat(listed.size).isGreaterThan(simplified.size)

    // The count the button shows is this one, and the bulk settle records exactly
    // that many rows.
    assertThat(repo.settleAll(g)).isEqualTo(simplified.size)
    assertThat(repo.settlements(g).first()).hasSize(simplified.size)
  }

  // `Settlement` carries no currency, so a row denominated in some expense's own
  // currency has nowhere to record what it was in.
  @Test
  fun `a settlement has no currency of its own`() = runTest {
    val (g, ids) = MoneyTest.members(repo, now, listOf("A", "B"))
    MoneyTest.bill(repo, now, g, ids[0], ids, 1000L, "EUR")

    // The unsimplified plan knows the row is EUR; the simplified one is already in
    // the group's own, which is why settleAll is always the simplified plan.
    assertThat(repo.plan(g, false).map { it.currencyCode }).containsExactly("EUR")
    assertThat(repo.plan(g, true).map { it.currencyCode }).containsExactly("USD")

    assertThat(Settlement::class.java.declaredFields.map { it.name })
        .doesNotContain("currencyCode")
  }

  @Test
  fun `a foreign row with no rate is a parity figure wearing the wrong label`() = runTest {
    val rates = emptyList<FxRate>()
    val row = 5000L // EUR 50.00

    // `pickRate` answers an unknown pair with parity, so the dialog prefills USD
    // 50.00 for a real figure of USD 54.50. A 9% error, recorded as fact.
    val prefilled = convertMinor(row, pickRate(rates, "EUR", "USD", now))
    assertThat(prefilled).isEqualTo(5000L)
    val priced = convertMinor(row, 1.09)
    assertThat(priced).isEqualTo(5450L)
    assertThat(priced - prefilled).isEqualTo(450L)

    assertThat(lookupRate(rates, "EUR", "USD", now)).isNull()
    // A pair that is the group's own currency needs no rate, and must not be gated.
    assertThat(lookupRate(rates, "USD", "USD", now)).isEqualTo(1.0)
  }
}
