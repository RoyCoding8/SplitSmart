package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistoryFilterTest {
  private fun ex(id: Long, note: String?, cat: Category = Category.FOOD, custom: Long? = null) =
      Expense(
          id = id,
          groupId = 1,
          payerId = 1,
          amountMinor = 1000,
          currencyCode = "USD",
          category = cat,
          note = note,
          dateEpoch = 1000,
          createdAt = 1000,
          customCatId = custom)

  private val custom = CustomCategory(id = 7, name = "Pizza", emoji = "🍕")

  // The row this screen draws is `note ?: catLabel`, so that is what the search has to
  // match. Matching the note alone put "🍕 Pizza" on screen while typing "pizza" reported
  // no match, and matching both failed the other way, offering a row whose text the note
  // had replaced with the category.
  @Test
  fun `a custom category label is findable including its emoji`() {
    val rows = listOf(ex(1, note = null, custom = 7))
    assertThat(rows.filter { historyMatches(it, "pizza", mapOf(7L to custom)) }).hasSize(1)
    // The row renders the emoji too, so the emoji is part of the text being searched.
    assertThat(rows.filter { historyMatches(it, "🍕", mapOf(7L to custom)) }).hasSize(1)
  }

  @Test
  fun `a built-in category is findable when there is no note`() {
    val rows = listOf(ex(1, note = null, cat = Category.FOOD))
    assertThat(rows.filter { historyMatches(it, "food", emptyMap()) }).hasSize(1)
  }

  // The row shows the note in preference to the category, so a search that also matched
  // the category would offer a row whose text does not contain what was typed.
  @Test
  fun `a note that hides its category is not findable by that category`() {
    val rows = listOf(ex(1, note = "team lunch", cat = Category.FOOD))
    assertThat(rows.filter { historyMatches(it, "food", emptyMap()) }).isEmpty()
    assertThat(rows.filter { historyMatches(it, "lunch", emptyMap()) }).hasSize(1)
  }

  @Test
  fun `a blank search matches every expense`() {
    val rows = listOf(ex(1, note = "dinner"), ex(2, note = null))
    assertThat(rows.filter { historyMatches(it, "", emptyMap()) }).hasSize(2)
    assertThat(rows.filter { historyMatches(it, "   ", emptyMap()) }).hasSize(2)
  }

  @Test
  fun `matching is case insensitive like the search field it backs`() {
    val rows = listOf(ex(1, note = "Taco Tuesday"))
    assertThat(rows.filter { historyMatches(it, "taco", emptyMap()) }).hasSize(1)
  }

  // A custom category id with no row behind it falls back to the built-in name, which is
  // what the row would show.
  @Test
  fun `a dangling custom id shows and matches the built-in name`() {
    val rows = listOf(ex(1, note = null, cat = Category.TRAVEL, custom = 99))
    assertThat(rows.filter { historyMatches(it, "travel", emptyMap()) }).hasSize(1)
  }
}
