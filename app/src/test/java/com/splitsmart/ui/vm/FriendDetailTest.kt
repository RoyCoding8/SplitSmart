package com.splitsmart.ui.vm

import com.google.common.truth.Truth.assertThat
import com.splitsmart.data.Category
import com.splitsmart.data.Expense
import org.junit.jupiter.api.Test

class FriendDetailTest {
  private fun ex(id: Long, payer: Long, amount: Long) =
      Expense(
          id = id,
          groupId = 1,
          payerId = payer,
          amountMinor = amount,
          currencyCode = "USD",
          category = Category.FOOD,
          dateEpoch = 0,
          createdAt = 0)

  @Test
  fun `totals split by payer`() {
    // A single-currency group converts at 1.0, so the split by payer is the
    // plain sum of each payer's expenses.
    val t =
        friendTotalsIn(
            listOf(ex(1, 7, 10000), ex(2, 9, 4000), ex(3, 7, 2000)), 7, "USD", emptyList())
    assertThat(t)
        .isEqualTo(FriendTotals(paidBySelf = 12000, paidByFriend = 4000, count = 3))
  }

  @Test
  fun `empty expenses zero out`() {
    assertThat(friendTotalsIn(emptyList(), 7, "USD", emptyList())).isEqualTo(FriendTotals(0, 0, 0))
  }

  @Test
  fun `settle direction follows net`() {
    assertThat(settleTriple(500, 7, 9)).isEqualTo(Triple(9L, 7L, 500L))
    assertThat(settleTriple(-500, 7, 9)).isEqualTo(Triple(7L, 9L, 500L))
    assertThat(settleTriple(0, 7, 9)).isNull()
  }
}
