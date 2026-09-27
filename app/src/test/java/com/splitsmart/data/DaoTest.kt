package com.splitsmart.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest : DbTest() {

  @Test
  fun `group member expense shares round trip`() = runTest {
    val g = db.groups().insert(Group(name = "Trip", currencyCode = "USD", createdAt = now))
    val a = db.members().insert(Member(groupId = g, name = "A", createdAt = now))
    val b = db.members().insert(Member(groupId = g, name = "B", createdAt = now))
    val e =
        db.expenses()
            .insert(
                Expense(
                    groupId = g,
                    payerId = a,
                    amountMinor = 10000,
                    currencyCode = "USD",
                    category = Category.FOOD,
                    dateEpoch = now,
                    createdAt = now))
    db.expenses().putShares(listOf(ExpenseShare(e, a, 5000), ExpenseShare(e, b, 5000)))
    db.expenses().expenses(g).test {
      assertThat(awaitItem().map { it.id }).containsExactly(e)
      cancelAndIgnoreRemainingEvents()
    }
    assertThat(db.expenses().shares(e).sumOf { it.owedMinor }).isEqualTo(10000L)
    assertThat(db.members().refCount(b)).isEqualTo(1)
  }

  @Test
  fun `cascade delete wipes group data`() = runTest {
    val g = db.groups().insert(Group(name = "X", currencyCode = "EUR", createdAt = now))
    val a = db.members().insert(Member(groupId = g, name = "A", createdAt = now))
    db.expenses()
        .insert(
            Expense(
                groupId = g,
                payerId = a,
                amountMinor = 100,
                currencyCode = "EUR",
                category = Category.OTHER,
                dateEpoch = now,
                createdAt = now))
    db.groups().delete(g)
    db.members().members(g).test {
      assertThat(awaitItem()).isEmpty()
      cancelAndIgnoreRemainingEvents()
    }
    db.expenses().expenses(g).test {
      assertThat(awaitItem()).isEmpty()
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `settlements and templates persist`() = runTest {
    val g = db.groups().insert(Group(name = "Y", currencyCode = "USD", createdAt = now))
    val a = db.members().insert(Member(groupId = g, name = "A", createdAt = now))
    val b = db.members().insert(Member(groupId = g, name = "B", createdAt = now))
    db.expenses()
        .insertSettlement(
            Settlement(groupId = g, fromId = b, toId = a, amountMinor = 2500, dateEpoch = now))
    db.expenses()
        .insertTemplate(
            RecurringTemplate(
                groupId = g,
                payerId = a,
                amountMinor = 5000,
                category = Category.RENT,
                splitRule = "{}",
                frequency = Frequency.MONTHLY,
                nextDueEpoch = now))
    db.expenses().settlements(g).test {
      assertThat(awaitItem().single().amountMinor).isEqualTo(2500L)
      cancelAndIgnoreRemainingEvents()
    }
    db.expenses().templates(g).test {
      assertThat(awaitItem().single().frequency).isEqualTo(Frequency.MONTHLY)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
