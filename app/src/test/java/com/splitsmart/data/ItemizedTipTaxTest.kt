package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import com.splitsmart.core.splits.TipMode
import com.splitsmart.core.splits.itemized
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The editor offers Tip and Tax for an itemized bill and folds both into the total it
// saves, so both have to be stored: a 57.00 bill with 5.00 of tip and 2.00 of tax that
// reopens as 50.00 looks correct in the Total field, and empty Tip and Tax look like
// values that were never typed. The people who owed the tip stop owing it.
@RunWith(RobolectricTestRunner::class)
class ItemizedTipTaxTest : DbTest() {

  private suspend fun itemizedBill(
      gid: Long,
      payer: Long,
      members: List<Long>,
      subs: List<Long>,
      tip: Long,
      tax: Long
  ) {
    val sub = subs.sum()
    val total = sub + tip + tax
    // The shares are the subtotals with the tip and tax folded in, which is what the editor
    // stores: the item rows keep the subtotals and the total keeps the fold, and that gap
    // is what a reopen has to survive.
    val shares =
        itemized(
            subs.withIndex().associate { (i, v) -> members[i] to v }, sub, tip, tax, TipMode.EQUAL)
    repo.saveExpense(
        Expense(
            groupId = gid,
            payerId = payer,
            amountMinor = total,
            currencyCode = "USD",
            category = Category.FOOD,
            note = "dinner",
            dateEpoch = now,
            createdAt = now,
            tipMinor = tip,
            taxMinor = tax),
        shares.keys.toList(),
        SplitInput(SplitType.EXACT, shares),
        items = subs.withIndex().map { (i, v) -> ExpenseItem(expenseId = 0L, label = "p$i items", amountMinor = v) })
  }

  @Test
  fun `an itemized bill keeps its tip and tax when it is reopened`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    // Alice 20.00, Bob 30.00, tip 5.00, tax 2.00.
    itemizedBill(g, a, listOf(a, b), listOf(2000L, 3000L), tip = 500L, tax = 200L)

    val stored = repo.expenses(g).first().single()
    assertThat(stored.amountMinor).isEqualTo(5700L)
    assertThat(stored.tipMinor).isEqualTo(500L)
    assertThat(stored.taxMinor).isEqualTo(200L)
  }

  @Test
  fun `an itemized bill survives a backup round trip with its tip and tax`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    itemizedBill(g, a, listOf(a, b), listOf(2000L, 3000L), tip = 500L, tax = 200L)

    val json = Exporter(repo).backup()
    db.groups().delete(g)
    Exporter(repo).restore(json)

    val gid = repo.allGroups().first().single().id
    val restored = repo.expenses(gid).first().single()
    assertThat(restored.amountMinor).isEqualTo(5700L)
    assertThat(restored.tipMinor).isEqualTo(500L)
    assertThat(restored.taxMinor).isEqualTo(200L)
    // The sub-bills still add up to the subtotal, not the total that was paid:
    // 20.00 + 30.00 is 50.00, not the 57.00.
    assertThat(repo.items(restored.id).sumOf { it.amountMinor }).isEqualTo(5000L)
  }

  @Test
  fun `a bill with no tip and tax stores zeros rather than nulls`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    itemizedBill(g, a, listOf(a, b), listOf(2000L, 3000L), tip = 0L, tax = 0L)
    val stored = repo.expenses(g).first().single()
    assertThat(stored.tipMinor).isEqualTo(0L)
    assertThat(stored.taxMinor).isEqualTo(0L)
    assertThat(stored.amountMinor).isEqualTo(5000L)
  }
}
