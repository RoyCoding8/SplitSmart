package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class StatsTest {
  private fun ex(id: Long, cat: Category, amt: Long, at: Long, payer: Long = 1) =
      Expense(
          id = id,
          groupId = 1,
          payerId = payer,
          amountMinor = amt,
          currencyCode = "USD",
          category = cat,
          dateEpoch = at,
          createdAt = at)

  @Test
  fun `totals by payer names resolved by caller`() {
    val out =
        totalsByPayer(
            listOf(
                ex(1, Category.FOOD, 300, 1, payer = 7), ex(2, Category.FOOD, 100, 2, payer = 9)))
    assertThat(out).containsExactly(7L to mapOf("USD" to 300L), 9L to mapOf("USD" to 100L)).inOrder()
  }

  @Test
  fun `monthly buckets use utc month start`() {
    val jan15 = 1_705_324_800_000L
    val feb2 = 1_707_667_200_000L
    val out =
        monthlyTotals(listOf(ex(1, Category.FOOD, 300, jan15), ex(2, Category.FOOD, 100, feb2)))
    assertThat(out).hasSize(2)
    assertThat(out[0].second).containsExactly("USD", 300L)
    assertThat(out[1].second).containsExactly("USD", 100L)
    assertThat(out[0].first).isLessThan(out[1].first)
  }

  @Test
  fun `empty inputs yield empty stats`() {
    assertThat(totalsByPayer(emptyList())).isEmpty()
    assertThat(monthlyTotals(emptyList())).isEmpty()
  }

  // A month is a bucket the caller draws a bar for, so its edge is the device's idea of
  // where the month starts. Grouping in UTC put a Kathmandu expense filed at 00:30 local
  // -- already the 1st there -- on the previous month, and the group list and the chart
  // disagreed about which month the spend belonged to.
  @Test
  fun `monthly buckets honor the device time zone`() {
    val zone = ZoneId.of("Asia/Kathmandu")
    val at = LocalDateTime.of(2026, 9, 1, 0, 30).atZone(zone).toInstant().toEpochMilli()
    val out = monthlyTotals(listOf(ex(1, Category.FOOD, 100, at)), zone)
    assertThat(out).hasSize(1)
    assertThat(out.single().first)
        .isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0).atZone(zone).toInstant().toEpochMilli())
  }

  // These functions have no rate table and no date context to pick a rate with, so they
  // group by currency rather than convert. Each bar is labelled with its own currency and
  // the charts group by it, so no total ever mixes two.
  @Test
  fun `currencies are never summed together`() {
    val usd = ex(1, Category.FOOD, 100, 1)
    val eur = ex(2, Category.FOOD, 100, 1).copy(currencyCode = "EUR")

    // Same payer, same category, same month, different currency: the shapes that would
    // otherwise collapse into a single 200 bar.
    assertThat(totalsByPayer(listOf(usd, eur)))
        .containsExactly(1L to mapOf("EUR" to 100L, "USD" to 100L))
    assertThat(totalsByCatLabel(listOf(usd, eur), emptyMap()))
        .containsExactly("FOOD" to mapOf("EUR" to 100L, "USD" to 100L))
    assertThat(monthlyTotals(listOf(usd, eur)))
        .hasSize(1)
    assertThat(monthlyTotals(listOf(usd, eur)).single().second)
        .containsExactly("EUR", 100L, "USD", 100L)
  }

  // A group that only ever spends in its own currency must produce exactly one entry per
  // bucket and the same numbers it always did, or every existing chart changes shape.
  @Test
  fun `a single-currency group is unchanged`() {
    val rows =
        listOf(
            ex(1, Category.FOOD, 300, 1, payer = 7),
            ex(2, Category.FOOD, 100, 1, payer = 9),
            ex(3, Category.TRAVEL, 50, 1, payer = 7))
    assertThat(totalsByPayer(rows))
        .containsExactly(7L to mapOf("USD" to 350L), 9L to mapOf("USD" to 100L))
    assertThat(totalsByCatLabel(rows, emptyMap()))
        .containsExactly("FOOD" to mapOf("USD" to 400L), "TRAVEL" to mapOf("USD" to 50L))
  }

  // The group list sums every expense in a group, so a group holding 100 USD and 100 EUR
  // must read "USD 1.00 · EUR 1.00" rather than "USD 2.00".
  @Test
  fun `a group total names every currency in it`() {
    val usd = ex(1, Category.FOOD, 100, 1)
    val eur = ex(2, Category.FOOD, 100, 1).copy(currencyCode = "EUR")

    assertThat(listOf(usd, eur).sumByCurrency()).containsExactly("EUR", 100L, "USD", 100L)
    // The group's own currency leads, so a single-currency group reads as before.
    assertThat(mapOf("USD" to 100L, "EUR" to 50L).format("USD")).isEqualTo("USD 1.00 · EUR 0.50")
    assertThat(mapOf("EUR" to 50L, "USD" to 100L).format("USD")).isEqualTo("USD 1.00 · EUR 0.50")
    // With no preference, or none of them matching, the order is stable.
    assertThat(mapOf("EUR" to 50L, "USD" to 100L).format()).isEqualTo("EUR 0.50 · USD 1.00")
    assertThat(mapOf("EUR" to 50L).format("USD")).isEqualTo("EUR 0.50")
    assertThat(emptyMap<String, Long>().format("USD")).isEqualTo("")
  }
}
