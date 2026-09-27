package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Delete and undo, which is a snapshot: whatever `deleteExpenseWithSnapshot` collected has
// to be the whole expense, or the undo is a partial one.
@RunWith(RobolectricTestRunner::class)
class ExpenseUndoTest : DbTest() {

  @Test
  fun `delete captures snapshot and restore re-inserts identical ledger`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 9000,
            currencyCode = "USD",
            category = Category.FOOD,
            note = "n",
            dateEpoch = now,
            createdAt = now),
        listOf(a, b),
        SplitInput(SplitType.EQUAL),
        mapOf(a to 6000L, b to 3000L),
        listOf(ExpenseItem(expenseId = 0, label = "x", amountMinor = 9000)),
    )
    val eid = db.expenses().expenses(g).first().single().id
    val snap = repo.deleteExpenseWithSnapshot(eid)
    assertThat(snap.expense.amountMinor).isEqualTo(9000L)
    assertThat(snap.shares.sumOf { it.owedMinor }).isEqualTo(9000L)
    assertThat(snap.payments.sumOf { it.paidMinor }).isEqualTo(9000L)
    // Asserting `values.sum() == 0` here would pass whether the map is empty because the
    // expense is gone or populated because it is still there, so it proves nothing.
    assertThat(repo.nets(g).first()).isEmpty()
    assertThat(db.expenses().expenses(g).first()).isEmpty()
    repo.restoreSnapshot(snap)
    val back = db.expenses().expenses(g).first().single()
    assertThat(back.amountMinor).isEqualTo(9000L)
    assertThat(back.note).isEqualTo("n")
    assertThat(db.expenses().shares(back.id).sumOf { it.owedMinor }).isEqualTo(9000L)
    assertThat(db.expenses().payments(back.id).sumOf { it.paidMinor }).isEqualTo(9000L)
    assertThat(db.expenses().items(back.id).single().label).isEqualTo("x")
    assertThat(repo.nets(g).first()).containsExactlyEntriesIn(mapOf(a to 1500L, b to -1500L))
  }

  // Comments hang off the expense with a CASCADE, so a delete that does not collect them
  // takes the thread with it and the undo brings back a ledger that reads as complete.
  @Test
  fun `undo brings back the comments as well as the ledger`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 9000, listOf(a, b), now)
    val eid = repo.expenses(g).first().single().id
    repo.addComment(eid, a, "was this the pasta place?")
    repo.addComment(eid, b, "yes")
    assertThat(repo.comments(eid).first()).hasSize(2)

    val snap = repo.deleteExpenseWithSnapshot(eid)
    assertThat(snap.comments.map { it.text })
        .containsExactly("was this the pasta place?", "yes")
        .inOrder()

    repo.restoreSnapshot(snap)
    val back = repo.expenses(g).first().single()
    assertThat(repo.comments(back.id).first().map { it.text })
        .containsExactly("was this the pasta place?", "yes")
        .inOrder()
    // And the authors survive, not just the text.
    assertThat(repo.comments(back.id).first().map { it.authorId }).containsExactly(a, b).inOrder()
  }

  @Test
  fun `full expense bundle loads shares payments items`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val a = repo.saveMember(Member(groupId = g, name = "A", createdAt = now))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            currencyCode = "USD",
            category = Category.OTHER,
            dateEpoch = now,
            createdAt = now),
        listOf(a),
        SplitInput(SplitType.EQUAL))
    val eid = db.expenses().expenses(g).first().single().id
    val b = repo.expenseBundle(eid)!!
    assertThat(b.shares).containsExactlyEntriesIn(mapOf(a to 100L))
    assertThat(b.payments).containsExactlyEntriesIn(mapOf(a to 100L))
    assertThat(repo.expenseBundle(999)).isNull()
  }
}
