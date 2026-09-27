package com.splitsmart.data

import com.splitsmart.core.splits.allPays

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MultiPayerTest : DbTest() {

  // `equal` hands out whole minor units, so 1 unit across three participants is 1/0/0 --
  // the first participant pays the cent and the other two pay nothing. `saveExpense`
  // refuses any payment map containing a zero, so the write throws, and the editor
  // computed exactly that map while letting Save be enabled. The payer of a
  // sub-cent-remainder share has to absorb the remainder instead.
  @Test
  fun `a bill too small to divide keeps the whole amount payable`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B", "C")
    val (a, b, c) = members

    val pays = allPays(1L, listOf(a, b, c))
    assertThat(pays).containsEntry(a, 1L)
    assertThat(pays.keys).containsExactly(a)
    assertThat(pays.values.sum()).isEqualTo(1L)

    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 1L,
            currencyCode = "USD",
            category = Category.OTHER,
            dateEpoch = now,
            createdAt = now),
        listOf(a, b, c),
        SplitInput(SplitType.EQUAL),
        payments = pays)

    val back = repo.expenses(g).first().single()
    assertThat(repo.expenseBundle(back.id)!!.payments.values.sum()).isEqualTo(1L)
    assertThat(repo.expenseBundle(back.id)!!.payments.values.all { it > 0 }).isTrue()
  }

  // The counterweight to the case above: with an even division there is no remainder to
  // absorb, so the whole group still pays its own share.
  @Test
  fun `a bill that divides evenly is still split equally`() {
    assertThat(allPays(900L, listOf(7L, 8L, 9L))).containsExactly(7L, 300L, 8L, 300L, 9L, 300L)
  }
}
