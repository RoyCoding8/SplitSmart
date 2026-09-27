package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FxTest {
  @Test
  fun `parseRate accepts positive decimals only`() {
    assertThat(parseRate("1.1")).isEqualTo(1.1)
    assertThat(parseRate(" 150 ")).isEqualTo(150.0)
    assertThat(parseRate("0")).isNull()
    assertThat(parseRate("-2")).isNull()
    assertThat(parseRate("NaN")).isNull()
    assertThat(parseRate("abc")).isNull()
    assertThat(parseRate("")).isNull()
  }

  // `saveRate` refuses an unusable one, so the only door is a row that got past it. The
  // lookup is the boundary every reader passes through, so that is where the check belongs:
  // an unusable stored rate is skipped exactly the way a missing one is, and the caller gets
  // parity while the predicate reports the currency as unpriced.
  @Test
  fun `an unusable stored rate is treated as no rate rather than a crash`() {
    val zero = FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 0.0, timeEpoch = 900)
    val neg = FxRate(groupId = 1, fromCode = "GBP", toCode = "USD", rate = -2.0, timeEpoch = 900)
    val inf =
        FxRate(
            groupId = 1,
            fromCode = "JPY",
            toCode = "USD",
            rate = Double.POSITIVE_INFINITY,
            timeEpoch = 900)
    val nan =
        FxRate(groupId = 1, fromCode = "CHF", toCode = "USD", rate = Double.NaN, timeEpoch = 900)
    // Parity is the deliberate "no rate known" answer, so it must not throw, and the
    // predicate callers use to withhold a figure has to agree.
    assertThat(pickRate(listOf(zero), "EUR", "USD", 1000)).isEqualTo(1.0)
    assertThat(pickRate(listOf(neg), "GBP", "USD", 1000)).isEqualTo(1.0)
    assertThat(pickRate(listOf(inf), "JPY", "USD", 1000)).isEqualTo(1.0)
    assertThat(pickRate(listOf(nan), "CHF", "USD", 1000)).isEqualTo(1.0)
    assertThat(unconvertibleCurrencies(listOf(ex("EUR")), "USD", listOf(zero)))
        .containsExactly("EUR")
  }

  @Test
  fun `convertMinor is exact and half even`() {
    assertThat(convertMinor(10000, 1.0)).isEqualTo(10000)
    assertThat(convertMinor(10000, 1.1)).isEqualTo(11000)
    assertThat(convertMinor(10000, 0.005)).isEqualTo(50)
    assertThat(convertMinor(1, 0.5)).isEqualTo(0) // 0.5 -> 0 (even)
    assertThat(convertMinor(3, 0.5)).isEqualTo(2) // 1.5 -> 2 (even)
    assertThat(convertMinor(-10000, 1.1)).isEqualTo(-11000)
    assertThat(convertMinor(100, 1.0 / 3.0)).isEqualTo(33)
  }

  @Test
  fun `pickRate prefers latest rate at or before the expense`() {
    val old = FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 2.0, timeEpoch = 900)
    val future = FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 3.0, timeEpoch = 1100)
    assertThat(pickRate(listOf(old, future), "EUR", "USD", 1000)).isEqualTo(2.0)
  }

  @Test
  fun `pickRate falls back to latest overall when all rates are newer`() {
    val future = FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 3.0, timeEpoch = 1100)
    assertThat(pickRate(listOf(future), "EUR", "USD", 1000)).isEqualTo(3.0)
  }

  @Test
  fun `pickRate inverts the reverse pair`() {
    val rev = FxRate(groupId = 1, fromCode = "USD", toCode = "EUR", rate = 0.5, timeEpoch = 100)
    assertThat(pickRate(listOf(rev), "EUR", "USD", 1000)).isWithin(1e-12).of(2.0)
  }

  @Test
  fun `pickRate is parity without a rate and identity for same currency`() {
    assertThat(pickRate(emptyList(), "EUR", "USD", 1000)).isEqualTo(1.0)
    assertThat(pickRate(emptyList(), "USD", "USD", 1000)).isEqualTo(1.0)
  }

  @Test
  fun `totalIn converts mixed currencies`() {
    val rates =
        listOf(FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 1.1, timeEpoch = 100))
    val exps =
        listOf(
            Expense(
                groupId = 1,
                payerId = 1,
                amountMinor = 10000,
                currencyCode = "USD",
                category = Category.FOOD,
                dateEpoch = 1000,
                createdAt = 1000),
            Expense(
                groupId = 1,
                payerId = 1,
                amountMinor = 10000,
                currencyCode = "EUR",
                category = Category.FOOD,
                dateEpoch = 1000,
                createdAt = 1000),
        )
    assertThat(totalIn(exps, "USD", rates)).isEqualTo(21000)
  }

  @Test
  fun `rateLine formats compactly`() {
    assertThat(
            rateLine(
                FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 1.1, timeEpoch = 1)))
        .isEqualTo("1 EUR = 1.1 USD")
  }

  private fun ex(cur: String, at: Long = 1000) =
      Expense(
          groupId = 1,
          payerId = 1,
          amountMinor = 5000,
          currencyCode = cur,
          category = Category.OTHER,
          dateEpoch = at,
          createdAt = at)

  // [unconvertibleCurrencies] has to lean on the same lookup [pickRate] uses, or a pair
  // that resolves by inversion is reported as missing while the app quietly converts it.
  @Test
  fun `a foreign expense with no rate is unconvertible and the pair names itself`() {
    assertThat(unconvertibleCurrencies(listOf(ex("EUR")), "USD", emptyList()))
        .containsExactly("EUR")
  }

  @Test
  fun `a rate in the pair makes it convertible and the list empties`() {
    val rates = listOf(FxRate(groupId = 1, fromCode = "EUR", toCode = "USD", rate = 1.1, timeEpoch = 100))
    assertThat(unconvertibleCurrencies(listOf(ex("EUR")), "USD", rates)).isEmpty()
  }

  // The reverse pair is a real rate: a group holding a USD→GBP rate can convert a GBP
  // expense by inversion, so reporting it missing would put a warning on screen for a
  // figure the app already computed honestly.
  @Test
  fun `a reverse rate counts because pickRate inverts it`() {
    val rev = listOf(FxRate(groupId = 1, fromCode = "USD", toCode = "GBP", rate = 0.5, timeEpoch = 100))
    assertThat(unconvertibleCurrencies(listOf(ex("GBP")), "USD", rev)).isEmpty()
    // ...and the same table in the wrong direction is not a rate for this pair.
    assertThat(unconvertibleCurrencies(listOf(ex("EUR")), "USD", rev)).containsExactly("EUR")
  }

  // A rate for a different pair says nothing about this one: a USD→GBP rate in a USD
  // group with a EUR expense is a rate, but not a rate for EUR.
  @Test
  fun `a rate for a different pair does not make a currency convertible`() {
    val unrelated = listOf(FxRate(groupId = 1, fromCode = "USD", toCode = "GBP", rate = 0.8, timeEpoch = 100))
    assertThat(unconvertibleCurrencies(listOf(ex("EUR")), "USD", unrelated))
        .containsExactly("EUR")
  }

  @Test
  fun `every unconvertible currency is named once however many expenses carry it`() {
    val rates = listOf(FxRate(groupId = 1, fromCode = "GBP", toCode = "USD", rate = 1.3, timeEpoch = 100))
    val exps = listOf(ex("EUR", 10), ex("EUR", 20), ex("JPY", 30), ex("USD", 40), ex("GBP", 50))
    assertThat(unconvertibleCurrencies(exps, "USD", rates)).containsExactly("EUR", "JPY").inOrder()
  }

  @Test
  fun `a group spending only in its own currency has nothing to report`() {
    assertThat(unconvertibleCurrencies(listOf(ex("USD"), ex("usd")), "USD", emptyList())).isEmpty()
    assertThat(unconvertibleCurrencies(emptyList(), "USD", emptyList())).isEmpty()
  }

  // The amount is exact in its own currency and the conversion simply never happened, so
  // telling the reader the figure may be wrong is a claim about a number never computed.
  @Test
  fun `the note names the missing rate and never calls the amount wrong`() {
    val one = unpricedNote(listOf("EUR"))
    assertThat(one).contains("EUR")
    assertThat(one).contains("No rate")
    assertThat(one).doesNotContain("wrong")
    assertThat(one).doesNotContain("incorrect")
    assertThat(unpricedNote(listOf("EUR", "JPY"))).contains("EUR and JPY")
    assertThat(unpricedNote(listOf("EUR", "JPY"))).contains("rates")
    assertThat(unpricedNote(emptyList())).isEmpty()
  }
}
