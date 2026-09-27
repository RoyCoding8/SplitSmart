package com.splitsmart.data

import com.splitsmart.ui.vm.money
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Statistics are reported per currency, never as a single number. These functions take a bare
 * expense list with no rate table and no date context to choose a rate with, and inventing one
 * would be worse than not converting: 100 USD and 100 EUR charted as one "200 USD" bar cannot be
 * reconciled against the balances screen, which does convert.
 */
typealias PerCurrency = Map<String, Long>

fun List<Expense>.sumByCurrency(): PerCurrency =
    groupBy { it.currencyCode }
        .mapValues { (_, v) -> v.sumOf { it.amountMinor } }
        .toSortedMap()

fun totalsByPayer(expenses: List<Expense>): List<Pair<Long, PerCurrency>> =
    expenses
        .groupBy { it.payerId }
        .mapValues { (_, v) -> v.sumByCurrency() }
        .toList()
        .sortedByDescending { (_, cur) -> cur.values.max() }

fun catLabel(ex: Expense, customs: Map<Long, CustomCategory>): String =
    ex.customCatId?.let { customs[it]?.let { c -> "${c.emoji} ${c.name}" } } ?: ex.category.name

/** Whether this History row survives the search box. The row is headed by `note ?: catLabel`, so a
 * note-less expense drawn as "🍕 Pizza" has to be findable by typing "pizza". */
fun historyMatches(ex: Expense, query: String, customs: Map<Long, CustomCategory>): Boolean =
    query.isBlank() || (ex.note ?: catLabel(ex, customs)).contains(query, ignoreCase = true)

fun totalsByCatLabel(
    expenses: List<Expense>,
    customs: Map<Long, CustomCategory>
): List<Pair<String, PerCurrency>> =
    expenses
        .groupBy { catLabel(it, customs) }
        .mapValues { (_, v) -> v.sumByCurrency() }
        .toList()
        .sortedByDescending { (_, cur) -> cur.values.max() }

fun monthlyTotals(
    expenses: List<Expense>,
    zone: ZoneId = ZoneId.systemDefault()
): List<Pair<Long, PerCurrency>> =
    expenses
        .groupBy {
          Instant.ofEpochMilli(it.dateEpoch)
              .atZone(zone)
              .truncatedTo(ChronoUnit.DAYS)
              .withDayOfMonth(1)
              .toInstant()
              .toEpochMilli()
        }
        .mapValues { (_, v) -> v.sumByCurrency() }
        .toList()
        .sortedBy { it.first }

/**
 * A per-currency total as text: "USD 120.00", or "USD 120.00 · EUR 40.00" when
 * the bucket holds more than one. Every figure a user reads has to name its
 * currency, and the group's own goes first when it is among them.
 */
fun PerCurrency.format(preferred: String? = null): String {
  if (isEmpty()) return ""
  val ordered =
      if (preferred != null && containsKey(preferred)) listOf(preferred) + (keys - preferred)
      else keys.toList()
  return ordered.joinToString(" · ") { money(getValue(it), it) }
}
