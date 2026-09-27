package com.splitsmart.ui.vm

import com.splitsmart.core.splits.checkedSum
import com.splitsmart.core.splits.largestRemainder
import com.splitsmart.core.splits.sumsTo
import com.splitsmart.data.SplitInput
import com.splitsmart.data.SplitType
import java.math.BigDecimal

data class Parsed(val parts: List<Long>, val input: SplitInput, val valid: Boolean)

fun majorToMinor(s: String): Long? =
    try {
      BigDecimal(s).movePointRight(2).longValueExact()
    } catch (_: Exception) {
      null
    }

/** The included rows' text as minor units, or null if any row is unreadable. */
private fun minorOf(inc: List<SplitRow>): Map<Long, Long>? {
  val v = inc.associate { it.id to majorToMinor(it.text) }
  return if (v.values.any { it == null || it < 0 }) null else v.mapValues { it.value!! }
}

/**
 * The per-row text that would parse back to these owed amounts under [type].
 *
 * Owed amounts alone do not record what the user typed -- a 60/20/20 percent split
 * and a hand-typed exact one produce identical amounts -- so reopening an expense
 * has to invert the arithmetic for the stored type. The whole set is converted
 * once: a percent split's basis points and a shares split's weights are only
 * defined across every row. A row with no faithful text is left out of the map
 * rather than given a value that parses to something else; EQUAL yields "".
 *
 * Owed amounts are never negative here: the only caller passes what `calcShares`
 * wrote, and every split type requires non-negative weights. No guard for it.
 */
fun splitRowTexts(
    owedByMember: Map<Long, Long>,
    totalMinor: Long,
    type: SplitType
): Map<Long, String> {
  if (owedByMember.isEmpty()) return emptyMap()
  return when (type) {
    SplitType.EQUAL -> owedByMember.keys.associateWith { "" }
    // The text is parsed by majorToMinor, so it must carry every cent. A Double
    // starts dropping them past 2^53 minor units.
    SplitType.EXACT -> owedByMember.mapValues { minorToDecimal(it.value) }
    // Rounding each row's own share of the total would not preserve the sum:
    // 33.33% three times is 9999bps and the editor refuses a percent split that
    // does not come to a hundred. So the 10000 basis points are apportioned once
    // and the shortfall handed out rather than lost. The runCatching is
    // load-bearing: a bill past 9.2e14 minor units overflows.
    SplitType.PERCENT ->
        if (totalMinor <= 0) emptyMap()
        else
            runCatching { largestRemainder(10000L, owedByMember, totalMinor) }
                .getOrNull()
                ?.mapValues { minorToDecimal(it.value) }
                ?: emptyMap()
    // Smallest whole-number weights proportional to the owed amounts, so a 1:2
    // split shows as "1" and "2" rather than "0.5" and "1".
    //
    // The divisor is the gcd of the whole set. A per-row divisor does not keep
    // the rows proportional to each other: 2:3:4 of 1000 comes out 223, 333 and
    // 444, which render against the total as 223, 333 and 111.
    //
    // A zero share is a weight of zero, which shares() accepts. Reducing it away
    // has no answer: the gcd of a zero and anything divides out to zero, the set
    // is declared impossible, and every row comes back empty.
    SplitType.SHARES -> {
      val g = owedByMember.values.filter { it > 0 }.reduceOrNull(::gcd) ?: return emptyMap()
      owedByMember.mapValues { (if (it.value == 0L) 0L else it.value / g).toString() }
    }
  }
}

private fun gcd(a: Long, b: Long): Long {
  var x = a
  var y = b
  while (y != 0L) {
    val t = x % y
    x = y
    y = t
  }
  return if (x < 0) -x else x
}

fun parseInput(total: Long, rows: List<SplitRow>, type: SplitType): Parsed {
  val inc = rows.filter { it.included }
  if (inc.isEmpty() || total <= 0) return Parsed(emptyList(), SplitInput(type), false)
  val ids = inc.map { it.id }
  fun invalid() = Parsed(ids, SplitInput(type), false)
  return when (type) {
    SplitType.EQUAL -> Parsed(ids, SplitInput(type), true)
    SplitType.EXACT -> {
      val m = minorOf(inc)
      if (m == null) invalid()
      else Parsed(ids, SplitInput(type, m), sumsTo(m.values, total))
    }
    SplitType.PERCENT -> {
      val m = minorOf(inc)
      if (m == null || m.values.any { it > Int.MAX_VALUE }) invalid()
      else
          Parsed(
              ids,
              SplitInput(type, bps = m.mapValues { it.value.toInt() }),
              sumsTo(m.values, 10000L))
    }
    // A weight of zero is accepted because shares() accepts one and the ledger
    // already stores the state it produces. A negative or non-numeric row is
    // refused, and so is the all-zero set, which apportions nothing.
    SplitType.SHARES -> {
      val w = inc.associate { it.id to (it.text.toLongOrNull() ?: -1L) }
      if (w.values.any { it < 0 || it > Long.MAX_VALUE / total } || w.values.all { it == 0L })
          invalid()
      else
          try {
            checkedSum(w.values)
            Parsed(ids, SplitInput(type, w), true)
          } catch (_: ArithmeticException) {
            invalid()
          }
    }
  }
}
