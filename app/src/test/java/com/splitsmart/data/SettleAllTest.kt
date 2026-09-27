package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What a payment does to a balance, what it refuses to do, and what settling a group
// in bulk records.
@RunWith(RobolectricTestRunner::class)
class SettleAllTest : DbTest() {

  /** A 2000 bill A fronted and A and B share: A is owed 1000, B owes it. */
  private suspend fun debtOfAB(): Triple<Long, Long, Long> {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 2000, listOf(a, b), now)
    return Triple(g, a, b)
  }

  @Test
  fun `settleAll records full plan and zeroes balances`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B", "C")
    val (a, b, c) = members
    repo.charge(g, a, 9000, listOf(a, b, c), now)

    assertThat(repo.settleAll(g)).isEqualTo(2)
    assertThat(repo.plan(g, true)).isEmpty()
    assertThat(repo.settlements(g).first()).hasSize(2)
  }

  @Test
  fun `settleAll on settled group records nothing`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    assertThat(repo.settleAll(g)).isEqualTo(0)
  }

  // The nets a bulk settle works from were converted at `pickRate`'s parity figure, so
  // without this the payments it records are amounts the group never agreed to, written
  // under the group's own currency. Settling one row at a time already refuses it.
  @Test
  fun `settleAll refuses when a currency has no rate rather than recording parity`() =
      runTest {
        val (g, members) = repo.newGroup(now, "A", "B")
        val (a, b) = members
        repo.charge(g, a, 5000, listOf(a, b), now, currencyCode = "EUR")

        val err = runCatching { repo.settleAll(g) }.exceptionOrNull()
        assertThat(err).isNotNull()
        assertThat(err!!.message).contains("EUR")
        // Refused means refused: nothing recorded and the balances still stand.
        assertThat(repo.settlements(g).first()).isEmpty()
        assertThat(repo.plan(g, true)).isNotEmpty()
      }

  @Test
  fun `settleAll proceeds once the missing rate is entered`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 5000, listOf(a, b), now, currencyCode = "EUR")
    repo.saveRate(FxRate(groupId = g, fromCode = "EUR", toCode = "USD", rate = 1.1, timeEpoch = now))

    assertThat(repo.settleAll(g)).isEqualTo(1)
    assertThat(repo.plan(g, true)).isEmpty()
  }

  @Test
  fun `summary text names debtors creditors and totals`() = runTest {
    val (g, members) = repo.newGroup(now, "Ann", "Bo", name = "Trip")
    val (a, b) = members
    repo.charge(g, a, 10000, listOf(a, b), now, note = "dinner")

    val s = repo.shareSummary(g)
    assertThat(s).contains("Trip")
    assertThat(s).contains("Bo pays Ann USD 50.00")
    assertThat(s).contains("dinner")
    assertThat(s).contains("Total spent USD 100.00")
  }

  @Test
  fun `summary of settled group says all clear`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    assertThat(repo.shareSummary(g)).contains("All settled")
  }

  // The sign has to fall for B and rise for A; the other way round means paying a debt
  // back creates one, and every settle-up points away from the person who has to pay.
  // The sum alone cannot catch it: a settlement leaves the total at zero either way.
  @Test
  fun `a payment reduces what the payer owes and settles up the receiver`() = runTest {
    val (g, a, b) = debtOfAB()

    // A fronted the bill, so the group owes A 1000 and B owes it.
    assertThat(repo.nets(g).first()).containsExactly(a, 1000L, b, -1000L)

    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 1000, dateEpoch = now))
    // B's debt is gone and A's claim is gone, rather than the two having swapped.
    assertThat(repo.nets(g).first()).containsExactly(a, 0L, b, 0L)
  }

  @Test
  fun `paying half leaves the payer owing the rest`() = runTest {
    val (g, a, b) = debtOfAB()

    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 400, dateEpoch = now))
    // Still 600 short, and still owed in the same direction.
    assertThat(repo.nets(g).first()).containsExactly(a, 600L, b, -600L)
  }

  // The debt has to be real at the moment it is written, not merely plausible when the
  // screen was built: a second tap on a row that is still on screen must fail rather
  // than push the balance past zero and read as owing the other way round.
  @Test
  fun `a settlement for a debt that does not exist is rejected`() = runTest {
    val (g, a, b) = debtOfAB()

    // A owes B 1000. Settling it in full is legal and leaves B owing nothing.
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 1000, dateEpoch = now))
    assertThat(repo.nets(g).first().values.sum()).isEqualTo(0L)

    // The stale row is still on the user's screen. Recording it again must fail
    // rather than invert the group's balance.
    try {
      repo.recordSettlement(
          Settlement(groupId = g, fromId = b, toId = a, amountMinor = 1000, dateEpoch = now))
      assert(false) { "expected the second settlement to be rejected" }
    } catch (e: IllegalArgumentException) {
      assertThat(e.message).contains("no longer owes")
    }
    assertThat(repo.settlements(g).first()).hasSize(1)
    assertThat(repo.nets(g).first().values.sum()).isEqualTo(0L)

    // And the reverse direction is equally refused: A never owed B in the first
    // place, so there is nothing to settle from A to B either.
    try {
      repo.recordSettlement(
          Settlement(groupId = g, fromId = a, toId = b, amountMinor = 100, dateEpoch = now))
      assert(false) { "expected a settlement with no underlying debt to be rejected" }
    } catch (e: IllegalArgumentException) {
      assertThat(e.message).contains("no longer owes")
    }
  }

  // The guard is on the sign of the balance, not the amount, because paying someone
  // back in instalments is normal. The bound that rejects is cumulative, not
  // per-payment.
  @Test
  fun `a debt can be settled in instalments`() = runTest {
    val (g, a, b) = debtOfAB()

    repeat(3) {
      repo.recordSettlement(
          Settlement(groupId = g, fromId = b, toId = a, amountMinor = 300, dateEpoch = now))
    }
    assertThat(repo.nets(g).first().values.sum()).isEqualTo(0L)
    // Only 900 of the 1000 was paid, so 100 is still owed and a fourth payment is
    // still legal. This is the boundary: one more than the debt.
    assertThat(repo.nets(g).first()[b]).isEqualTo(-100L)
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 100, dateEpoch = now))
    assertThat(repo.settlements(g).first()).hasSize(4)
  }

  // The sign guard is necessary but not sufficient: it stops a member who owes nothing
  // from paying at all, while saying nothing about how much. A member owing 1000 could
  // still post a single 5000 payment -- the check passes, the balance is inverted, and
  // every settle-up row built from it wants them to pay. Both bounds have to hold at
  // every prefix, and only together do they pin the balance to the debt.
  @Test
  fun `a payment larger than the debt is rejected rather than inverting the balance`() =
      runTest {
        val (g, a, b) = debtOfAB()

        // B owes A 1000. Paying 1500 of it settles the debt and 500 too many.
        try {
          repo.recordSettlement(
              Settlement(groupId = g, fromId = b, toId = a, amountMinor = 1500, dateEpoch = now))
          assert(false) { "expected an overpayment to be rejected" }
        } catch (e: IllegalArgumentException) {
          assertThat(e.message).contains("more than")
        }
        assertThat(repo.settlements(g).first()).isEmpty()
        // Untouched: B still owes the full 1000, rather than the group owing B 500.
        assertThat(repo.nets(g).first()).containsExactly(a, 1000L, b, -1000L)
      }

  // The unsimplified plan is built one expense at a time and greedy() runs independently
  // on each, so two expenses of the same shape emit the same transfer twice -- "Bob pays
  // Alice 50" twice -- which are one debt of 100 and which collided on the settle-up
  // list's per-pair key.
  @Test
  fun `an unsimplified plan lists a repeated pair once with the total owed`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repeat(2) { i -> repo.charge(g, a, 10000, listOf(a, b), now + i) }

    val raw = repo.plan(g, false)
    // One row, not two: the pair is unique so the list key is unique. A fronted
    // both bills, so B is the one paying.
    assertThat(raw.map { it.from to it.to }).containsExactly(b to a)
    // And it is the whole debt, so the one Settle button settles all of it.
    assertThat(raw.single().amountMinor).isEqualTo(10000L)
    assertThat(repo.plan(g, true).single().amountMinor).isEqualTo(10000L)
  }
}
