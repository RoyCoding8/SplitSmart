package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import com.splitsmart.core.splits.allPays
import com.splitsmart.core.splits.isSplitPayment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The editor is the only place an expense is rewritten, and it rewrites it whole:
// `replaceFull` clears the shares, the payments and the item rows before putting back
// whatever the editor hands it. So anything the editor fails to carry across its open is
// gone from the ledger the moment Save is tapped, and gone without a message -- the expense
// still looks right on screen, it is only the balances underneath that have moved.
@RunWith(RobolectricTestRunner::class)
class ReopenPreservesExpenseTest : DbTest() {

  private suspend fun group(vararg names: String) =
      repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now)).also { g ->
        names.forEach { n -> repo.saveMember(Member(groupId = g, name = n, createdAt = now)) }
      }

  private suspend fun members(g: Long) = repo.members(g).first().associate { it.name to it.id }

  // A reopen that hands the save nothing for payments writes the single-payer
  // default, and the balances move under a screen that still looks right.
  @Test
  fun `a save that drops the payment map moves the balances`() = runTest {
    val g = group("A", "B", "C")
    val ids = members(g)
    val (a, b, c) = listOf(ids.getValue("A"), ids.getValue("B"), ids.getValue("C"))

    fun expense() =
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 9000,
            currencyCode = "USD",
            category = Category.FOOD,
            dateEpoch = now,
            createdAt = now)

    // 60/40, which is the shape this editor cannot author for itself: `allPays` divides
    // evenly between the payers, so a bill like this can only arrive from a restore or a
    // foreign import. An even 50/50 would move the balances too, by half as much.
    val eid =
        repo.saveExpense(
            expense(),
            listOf(a, b, c),
            SplitInput(SplitType.EQUAL),
            payments = mapOf(a to 6000L, b to 3000L))
    assertThat(repo.nets(g).first()).containsExactly(a, 3000L, b, 0L, c, -3000L)

    // The save the editor used to make on every reopen: no payment map, so
    // saveExpense substitutes the single-payer default and replaceFull writes it.
    repo.saveExpense(expense().copy(id = eid), listOf(a, b, c), SplitInput(SplitType.EQUAL))

    assertThat(repo.expenseBundle(eid)!!.payments).containsExactly(a, 9000L)
    assertThat(repo.nets(g).first()).containsExactly(a, 6000L, b, -3000L, c, -3000L)
  }

  // `isSplitPayment` is what tells the editor there is a payment map worth carrying, so a
  // bill that reads as split but hands back nothing is the failure this has to rule out.
  @Test
  fun `carrying the bundle's payment map through a save keeps the balances`() = runTest {
    val g = group("A", "B", "C")
    val ids = members(g)
    val (a, b, c) = listOf(ids.getValue("A"), ids.getValue("B"), ids.getValue("C"))

    fun expense() =
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 9000,
            currencyCode = "USD",
            category = Category.FOOD,
            dateEpoch = now,
            createdAt = now)

    val eid =
        repo.saveExpense(
            expense(),
            listOf(a, b, c),
            SplitInput(SplitType.EQUAL),
            payments = mapOf(a to 6000L, b to 3000L))
    val before = repo.nets(g).first()

    val reopened = repo.expenseBundle(eid)!!
    assertThat(isSplitPayment(reopened.payments, reopened.expense.payerId, 9000L)).isTrue()
    assertThat(reopened.payments).containsExactly(a, 6000L, b, 3000L)

    repo.saveExpense(
        expense().copy(id = eid),
        listOf(a, b, c),
        SplitInput(SplitType.EQUAL),
        payments = reopened.payments)

    assertThat(repo.expenseBundle(eid)!!.payments).containsExactly(a, 6000L, b, 3000L)
    assertThat(repo.nets(g).first()).isEqualTo(before)
  }

  // The contract is a fixed point, not a row count: a reopened bill must save back
  // identically. A one-row map cannot answer that question on its own.
  @Test
  fun `a one row payment map is the fixed point of a reopen`() = runTest {
    val g = group("A", "B", "C")
    val ids = members(g)
    val (a, b, c) = listOf(ids.getValue("A"), ids.getValue("B"), ids.getValue("C"))

    // One cent across three payers, which is the ambiguous case.
    val oneCent = allPays(1L, listOf(a, b, c))
    assertThat(oneCent).containsExactly(a, 1L)
    assertThat(oneCent).isEqualTo(mapOf(a to 1L))
    assertThat(isSplitPayment(oneCent, a, 1L)).isFalse()

    // A real bill one person paid in full is the same shape, and stays put too.
    val onePayer = allPays(1000L, listOf(a))
    assertThat(isSplitPayment(onePayer, a, 1000L)).isFalse()

    val split = mapOf(a to 600L, b to 400L)
    assertThat(isSplitPayment(split, a, 1000L)).isTrue()
  }

  @Test
  fun `the default is the payer holding the whole amount`() {
    assertThat(isSplitPayment(mapOf(7L to 900L), 7L, 900L)).isFalse()
    assertThat(isSplitPayment(mapOf(7L to 500L, 8L to 400L), 7L, 900L)).isTrue()
    // A different member holding the whole amount is a different bill entirely.
    assertThat(isSplitPayment(mapOf(8L to 900L), 7L, 900L)).isTrue()
  }
}
