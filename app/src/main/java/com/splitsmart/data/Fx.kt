/**
 * Manual exchange rates: user-entered, timestamped.
 *
 * A rate means "1 [FxRate.fromCode] = [FxRate.rate] [FxRate.toCode]". Every currency is 100 minor
 * units per major unit (see [com.splitsmart.ui.vm.money]), so the ratio converts minor units
 * directly.
 */
package com.splitsmart.data

import java.math.BigDecimal
import java.math.RoundingMode

/** Parses a user-entered rate; null unless a finite positive decimal. */
fun parseRate(raw: String): Double? {
  val r = raw.trim().toDoubleOrNull() ?: return null
  return if (r.isFinite() && r > 0) r else null
}

/** Exact decimal math, no binary-float drift; rounds half-even to the nearest minor unit. */
fun convertMinor(amountMinor: Long, rate: Double): Long {
  require(rate.isFinite() && rate > 0) { "bad rate" }
  return BigDecimal(amountMinor)
      .multiply(BigDecimal(rate.toString()))
      .setScale(0, RoundingMode.HALF_EVEN)
      .longValueExact()
}

/**
 * The rate for [from]→[to] effective at [atEpoch], or null when the table holds no usable rate for
 * the pair. Identity for a same-currency pair: a real answer, not an absent one.
 *
 * A stored 0, negative or infinite rate is skipped exactly as a missing one is, so the caller falls
 * back to parity and `unconvertibleCurrencies` reports the currency as unpriced. Returning it
 * instead would reach [convertMinor], which throws.
 */
fun lookupRate(rates: List<FxRate>, from: String, to: String, atEpoch: Long): Double? {
  val f = from.uppercase()
  val t = to.uppercase()
  if (f == t) return 1.0
  val dir = rates.filter { it.fromCode == f && it.toCode == t }
  val rev = rates.filter { it.fromCode == t && it.toCode == f }
  // One priority list, each entry carrying the transform that makes it an answer for this
  // direction, so an unusable candidate ends the search at its own priority.
  val candidates =
      listOf<Pair<FxRate?, (Double) -> Double>>(
          dir.filter { it.timeEpoch <= atEpoch }.maxByOrNull { it.timeEpoch } to { it },
          dir.maxByOrNull { it.timeEpoch } to { it },
          (rev.filter { it.timeEpoch <= atEpoch }.maxByOrNull { it.timeEpoch }
              ?: rev.maxByOrNull { it.timeEpoch }) to { 1.0 / it })
  for ((r, use) in candidates) {
    if (r != null && r.rate.isFinite() && r.rate > 0) return use(r.rate)
  }
  return null
}

/**
 * [lookupRate]'s answer with a fabricated 1.0 in place of the null. Deliberate and test-pinned: a
 * caller with no way to say what it does not know gets parity rather than a crash. The real defect
 * is that no screen says a rate is missing -- use [lookupRate] and [unpricedNote] where the caller
 * can withhold a figure instead of printing one.
 */
fun pickRate(rates: List<FxRate>, from: String, to: String, atEpoch: Long): Double =
    lookupRate(rates, from, to, atEpoch) ?: 1.0

/**
 * The foreign currencies in this group that no rate can turn into [groupCurrency], so a screen knows
 * the figure it is about to print rests on [pickRate]'s parity fallback. A pair that resolves by
 * inversion counts as known, because [pickRate] uses it.
 *
 * A caller that cannot say what it does not know must not go through [pickRate]: settling a foreign
 * row through it records a parity figure under the group's own currency, and the balance it moves
 * is as wrong as the number on the row.
 */
fun unconvertibleCurrencies(
    expenses: List<Expense>,
    groupCurrency: String,
    rates: List<FxRate>
): List<String> =
    expenses
        .asSequence()
        .filter { it.currencyCode.uppercase() != groupCurrency.uppercase() }
        .filter { lookupRate(rates, it.currencyCode, groupCurrency, it.dateEpoch) == null }
        .map { it.currencyCode.uppercase() }
        .distinct()
        .sorted()
        .toList()

/**
 * What to tell the reader when a figure rests on no rate: the rate is missing, not the amount.
 * Saying the total may be wrong would be a claim the app cannot make.
 */
fun unpricedNote(unpriced: List<String>): String =
    if (unpriced.isEmpty()) ""
    else
        "No rate${if (unpriced.size == 1) "" else "s"} entered for ${unpriced.joinToString(" and ")}" +
            ", so this figure is not converted"

/** Sums expenses converted into [target] currency. */
fun totalIn(expenses: List<Expense>, target: String, rates: List<FxRate>): Long =
    expenses.sumOf {
      convertMinor(it.amountMinor, pickRate(rates, it.currencyCode, target, it.dateEpoch))
    }

fun rateLine(r: FxRate): String =
    "1 ${r.fromCode} = ${BigDecimal(r.rate.toString()).stripTrailingZeros().toPlainString()} ${r.toCode}"
