package com.splitsmart.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The ledger itself: what a bill does to the balances, what a payment is allowed to do to
// them, and the two ways a member can end up undeletable.
@RunWith(RobolectricTestRunner::class)
class RepositoryTest : DbTest() {

  @Test
  fun `expense nets and simplified plan`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 10000, listOf(a, b), now)
    repo.nets(g).test {
      assertThat(awaitItem()).containsExactlyEntriesIn(mapOf(a to 5000L, b to -5000L))
      cancelAndIgnoreRemainingEvents()
    }
    val plan = repo.plan(g, true)
    assertThat(plan.single().from).isEqualTo(b)
    assertThat(plan.single().to).isEqualTo(a)
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 5000, dateEpoch = now))
    assertThat(repo.plan(g, true)).isEmpty()
  }

  @Test
  fun `foreign currency expense is accepted and converted at stored rate`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 10000, listOf(a, b), now, currencyCode = "EUR")
    repo.saveRate(
        FxRate(groupId = g, fromCode = "EUR", toCode = "USD", rate = 2.0, timeEpoch = now - 1000))
    repo.nets(g).test {
      assertThat(awaitItem()).containsExactlyEntriesIn(mapOf(a to 10000L, b to -10000L))
      cancelAndIgnoreRemainingEvents()
    }
    try {
      repo.charge(g, a, 100, listOf(a), now, currencyCode = "US")
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
  }

  @Test
  fun `settlements reject self zero and negative`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    // A real debt has to exist before any of these are meaningful: a settlement is a
    // payment against a balance, and one written against nothing is refused.
    repo.charge(g, a, 1000, listOf(a, b), now)
    listOf(
            Settlement(groupId = g, fromId = a, toId = a, amountMinor = 100, dateEpoch = now),
            Settlement(groupId = g, fromId = b, toId = a, amountMinor = 0, dateEpoch = now),
            Settlement(groupId = g, fromId = b, toId = a, amountMinor = -5, dateEpoch = now),
            Settlement(groupId = g, fromId = 999, toId = a, amountMinor = 100, dateEpoch = now),
        )
        .forEach { s ->
          try {
            repo.recordSettlement(s)
            assert(false) { "expected for $s" }
          } catch (_: IllegalArgumentException) {}
        }
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 100, dateEpoch = now))
  }

  // A wrapped sum reads as a small ordinary amount rather than an error, so the user is
  // told they owe 0.00 when the arithmetic has actually left the representable range.
  @Test
  fun `a balance that cannot be represented fails rather than wrapping`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    // A pays a MAX-sized expense but owes almost none of it, so A's running net is within
    // one cent of the ceiling rather than half of it. An EQUAL split cannot produce this:
    // it hands A half the expense back immediately. Two such expenses put the sum past
    // Long.MAX_VALUE.
    val amt = Long.MAX_VALUE
    val owesA = 1L
    listOf(amt, amt).forEach {
      repo.saveExpense(
          Expense(
              groupId = g,
              payerId = a,
              amountMinor = it,
              currencyCode = "USD",
              category = Category.OTHER,
              dateEpoch = now,
              createdAt = now),
          listOf(a, b),
          SplitInput(SplitType.EXACT, mapOf(a to owesA, b to (it - owesA))))
    }
    try {
      val nets = repo.nets(g).first()
      assert(false) { "expected overflow to be rejected, got $nets" }
    } catch (_: ArithmeticException) {}
  }

  @Test
  fun `member with references cannot be deleted`() = runTest {
    val (g, members) = repo.newGroup(now, "A")
    val a = members.single()
    repo.charge(g, a, 100, listOf(a), now, category = Category.OTHER)
    try {
      repo.deleteMember(a)
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
  }

  // The comment row has no foreign key, so a delete that checks the other reference counts
  // without checking this one leaves it behind and the UI renders it against a name lookup
  // that no longer resolves: every one of that member's comments silently becomes
  // "Someone". The balance guards do not catch it, because a comment is not money.
  @Test
  fun `a member who wrote a comment cannot be deleted out from under it`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    // The expense is entirely B's, so A is in the group but touches no ledger at all: no
    // shares, no payments, no balance. Every other guard passes, so the comment has to be
    // the thing that stops the delete.
    repo.charge(g, b, 100, listOf(b), now)
    val e = db.expenses().expenses(g).first().single().id
    repo.addComment(e, a, "I paid for the tickets")
    try {
      repo.deleteMember(a)
      assert(false) { "expected the comment reference to block the delete" }
    } catch (ex: IllegalArgumentException) {
      assertThat(ex.message).contains("comment")
    }
    // Once the comment is gone the member deletes cleanly, so the guard is on the
    // reference and not on the member having ever spoken.
    val cid = db.comments().commentsFor(e).first().single().id
    db.comments().delete(cid)
    repo.deleteMember(a)
    assertThat(repo.members(g).first().map { it.name }).containsExactly("B")
  }
}
