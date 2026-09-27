package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import com.splitsmart.ui.vm.friendTotalsIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A figure that rests on no rate must not be printed, recorded or notified.
// `unconvertibleCurrencies` names the currencies no rate can convert, and the surfaces
// withhold the amount and say so instead. A signal the shape does not carry is a signal
// no caller can act on.
@RunWith(RobolectricTestRunner::class)
class UnpricedSurfacesTest : DbTest() {

  /** The group, the user, and the friend who paid. */
  private data class Friends(val gid: Long, val me: Long, val sam: Long)

  /** Sam paid EUR 50.00 for two, so I owe him half of a figure nobody priced. */
  private suspend fun friendsWithUnpricedDinner(): Friends {
    val (gid, ids) = MoneyTest.members(repo, now, listOf("Me", "Sam"))
    val (me, sam) = ids[0] to ids[1]
    MoneyTest.bill(repo, now, gid, sam, ids, 5000L, "EUR", note = "dinner")
    return Friends(gid, me, sam)
  }

  @Test
  fun `a friend total names the currency it could not convert`() = runTest {
    val (gid, me, _) = friendsWithUnpricedDinner()

    val totals = friendTotalsIn(repo.expenses(gid).first(), me, "USD", repo.rates(gid).first())

    // The parity sum is still what the arithmetic gives -- the nets are not wrong, their
    // input was invented -- and the currency that caused it travels with them, so a caller
    // can refuse to print the figure.
    assertThat(totals.unpriced).containsExactly("EUR")
  }

  @Test
  fun `a friend total with a rate entered names nothing`() = runTest {
    val (gid, me, _) = friendsWithUnpricedDinner()
    MoneyTest.rate(repo, gid, now, "EUR", "USD", 1.1, ageMillis = 0L)

    val totals = friendTotalsIn(repo.expenses(gid).first(), me, "USD", repo.rates(gid).first())

    assertThat(totals.unpriced).isEmpty()
  }

  @Test
  fun `a settle reminder withholds an amount that rests on no rate`() = runTest {
    val (_, _, sam) = friendsWithUnpricedDinner()
    repo.setNudge(sam, true)

    val nudge = repo.nudgedOutstanding().single()
    assertThat(nudge.memberName).isEqualTo("Sam")
    assertThat(nudge.unpriced).containsExactly("EUR")
  }

  @Test
  fun `a single settlement of an unpriced debt is refused`() = runTest {
    val (gid, me, sam) = friendsWithUnpricedDinner()
    // Sam paid, so the group owes him half of EUR 50.00 and I owe him. The debt is a
    // parity 2500, and the friend screen prefills exactly that figure.
    val debt = repo.nets(gid).first()[me] ?: 0L
    assertThat(debt).isEqualTo(-2500L)

    val err =
        runCatching {
              repo.recordSettlement(
                  Settlement(
                      groupId = gid, fromId = me, toId = sam, amountMinor = -debt, dateEpoch = now))
            }
            .exceptionOrNull()

    assertThat(err).isNotNull()
    assertThat(err!!.message).contains("EUR")
    // Nothing is written: the ledger does not hold a payment for a debt that was
    // never priced.
    assertThat(repo.settlements(gid).first()).isEmpty()
    assertThat(repo.nets(gid).first()[me]).isEqualTo(-2500L)
  }

  @Test
  fun `a single settlement proceeds once the rate is entered`() = runTest {
    val (gid, me, sam) = friendsWithUnpricedDinner()
    MoneyTest.rate(repo, gid, now, "EUR", "USD", 1.1, ageMillis = 0L)
    // 2500 at 1.1 is 2750, so the dialog now prefills a figure that depends on a
    // rate the user entered and this is no longer a parity number.
    val debt = repo.nets(gid).first()[me] ?: 0L
    assertThat(debt).isEqualTo(-2750L)

    repo.recordSettlement(
        Settlement(groupId = gid, fromId = me, toId = sam, amountMinor = -debt, dateEpoch = now))

    assertThat(repo.settlements(gid).first()).hasSize(1)
    assertThat(repo.nets(gid).first()[me]).isEqualTo(0L)
  }
}
