package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// `recordSettlement` refuses a payment larger than what the payer owes, which is what
// stops a balance inverting. Reading the net and then writing with a suspending gap
// between them left the guard true only of the instant it was read, so two callers that
// both read before either wrote both saw the same un-settled debt and both inserted --
// a 1000 debt took 2000. That is worse than an overshoot: the payer's net flips positive,
// and the `net < 0` half of the guard then refuses every later payment from them with
// "this member no longer owes anything", which is false.
//
// Real threads and a real barrier, because this is a race: `runTest`'s virtual time makes
// two coroutines take turns rather than overlap.
@RunWith(RobolectricTestRunner::class)
class ConcurrentSettlementTest : DbTest() {

  /** A 2000 bill A fronted and A and B share: B owes A 1000. */
  private suspend fun debtOfAB(): Triple<Long, Long, Long> {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 2000, listOf(a, b), now)
    return Triple(g, a, b)
  }

  @Test
  fun `two payments launched together cannot both clear the same debt`() = runBlocking {
    val (g, a, b) = debtOfAB()

    withContext(Dispatchers.IO) {
      coroutineScope {
        // Both coroutines are parked here until both have arrived, so neither
        // can finish its reads before the other starts.
        val gate = CyclicBarrier(2)
        val one =
            async(Dispatchers.IO) {
              gate.await(10, TimeUnit.SECONDS)
              runCatching {
                repo.recordSettlement(
                    Settlement(
                        groupId = g, fromId = b, toId = a, amountMinor = 1000, dateEpoch = now))
              }
            }
        val two =
            async(Dispatchers.IO) {
              gate.await(10, TimeUnit.SECONDS)
              runCatching {
                repo.recordSettlement(
                    Settlement(
                        groupId = g, fromId = b, toId = a, amountMinor = 1000, dateEpoch = now))
              }
            }
        one.await()
        two.await()
      }
    }

    // Exactly one payment of 1000 lands against a 1000 debt, and the balance is settled
    // rather than inverted -- inverted is the failure that matters, because the group
    // would owe the payer and every later payment from them would be refused.
    assertThat(repo.settlements(g).first()).hasSize(1)
    assertThat(repo.nets(g).first()[b]).isEqualTo(0L)
  }

  // `settleAll` settles from the same nets and takes the mutex, which gave the appearance
  // of serialization while the single-row path took no lock at all. Settle all and tap a
  // row, and the two writers each saw a clearable plan.
  @Test
  fun `a bulk settle and a single payment cannot both clear the same debt`() = runBlocking {
    val (g, a, b) = debtOfAB()

    withContext(Dispatchers.IO) {
      coroutineScope {
        val gate = CyclicBarrier(2)
        val bulk =
            async(Dispatchers.IO) {
              gate.await(10, TimeUnit.SECONDS)
              runCatching { repo.settleAll(g) }
            }
        val single =
            async(Dispatchers.IO) {
              gate.await(10, TimeUnit.SECONDS)
              runCatching {
                repo.recordSettlement(
                    Settlement(
                        groupId = g, fromId = b, toId = a, amountMinor = 1000, dateEpoch = now))
              }
            }
        bulk.await()
        single.await()
      }
    }

    // Whichever writer won, exactly one payment of 1000 lands and the balance is settled
    // rather than past it. The assertion is on the ledger, not on which call threw:
    // `settleAll` on an already-settled group returns 0, which is a success and not a
    // refusal. The debt is the invariant; the exception is not.
    assertThat(repo.settlements(g).first()).hasSize(1)
    assertThat(repo.nets(g).first()[b]).isEqualTo(0L)
  }
}
