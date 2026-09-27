package com.splitsmart.ui.vm

import com.google.common.truth.Truth.assertThat
import com.splitsmart.core.settle.Transfer
import com.splitsmart.data.FxRate
import org.junit.jupiter.api.Test

// A settlement stores a bare amount with no currency column, and the nets add it
// straight into the group-currency ledger, so the settle dialog is a group-currency
// form and every row it prefills has to be restated in the group currency.
class SettlementAmountTest {
  private val rates =
      listOf(FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 1.1, timeEpoch = 100))

  // A row already in the group currency must come back as the identical number
  // rather than one that has been through a rate and back.
  @Test
  fun `a row already in the group currency is unchanged`() {
    assertThat(settlementAmountIn(Transfer(7, 9, 300, "USD"), "USD", rates, 1000)).isEqualTo(300L)
    assertThat(settlementAmountIn(Transfer(7, 9, 1250, "USD"), "USD", emptyList(), 1000))
        .isEqualTo(1250L)
  }

  @Test
  fun `a row in a foreign currency is restated at the picked rate`() {
    assertThat(settlementAmountIn(Transfer(9, 7, 5000, "EUR"), "USD", rates, 1000))
        .isEqualTo(5500L)
  }

  // One plan, two rows, two currencies, and a single group-currency ledger
  // underneath both: the dialog is handed two numbers that are both group currency.
  @Test
  fun `two rows in two currencies both come back in the group currency`() {
    val plan = listOf(Transfer(9, 7, 5000, "EUR"), Transfer(7, 9, 300, "USD"))
    assertThat(plan.map { settlementAmountIn(it, "USD", rates, 1000) })
        .containsExactly(5500L, 300L)
        .inOrder()
  }

  // The caller supplies the epoch, so the row's own expense date cannot pin the
  // lookup to a stale rate.
  @Test
  fun `the rate is the one stamped at the settlement time`() {
    val later = rates + FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 2.0, timeEpoch = 5000)
    assertThat(settlementAmountIn(Transfer(9, 7, 5000, "EUR"), "USD", later, 1000))
        .isEqualTo(5500L)
    assertThat(settlementAmountIn(Transfer(9, 7, 5000, "EUR"), "USD", later, 6000))
        .isEqualTo(10000L)
  }
}
