package com.splitsmart.core.splits

fun equal(total: Long, ids: List<Long>): Map<Long, Long> {
  require(total >= 0 && ids.isNotEmpty() && ids.toSet().size == ids.size) { "ids must be distinct" }
  val b = total / ids.size
  return ids.mapIndexed { i, id -> id to b + if (i < total % ids.size) 1 else 0 }.toMap()
}

/**
 * [equal] for a multi-payer bill, where a payment of nothing is not a payment and
 * `saveExpense` refuses one. When the remainder leaves a single payer, that payer
 * absorbs it: the money is left where the even division put it, and every payment
 * stays positive and still sums to the total.
 */
fun allPays(total: Long, ids: List<Long>): Map<Long, Long> {
  val split = equal(total, ids).filterValues { it > 0L }
  return if (split.size == 1) mapOf(split.keys.first() to total) else split
}

/**
 * Whether a payment map is a genuine split between payers, or the single-payer
 * default the editor would substitute.
 *
 * Asked as "is this the default" rather than "how many rows" because [allPays] can
 * leave a real multi-payer bill holding exactly one row, and the default is a
 * one-row map too. Which member holds it is the only thing that separates them.
 */
fun isSplitPayment(pays: Map<Long, Long>, payerId: Long, totalMinor: Long): Boolean =
    pays != mapOf(payerId to totalMinor)

fun checkedSum(values: Collection<Long>): Long {
  var s = 0L
  values.forEach { s = Math.addExact(s, it) }
  return s
}

fun sumsTo(values: Collection<Long>, total: Long): Boolean =
    try {
      checkedSum(values) == total
    } catch (_: ArithmeticException) {
      false
    }

fun exact(total: Long, amounts: Map<Long, Long>): Map<Long, Long> {
  require(amounts.isNotEmpty() && amounts.values.all { it >= 0 } && sumsTo(amounts.values, total)) {
    "exact shares must sum to total"
  }
  return amounts.toMap()
}

fun percent(total: Long, bps: Map<Long, Int>): Map<Long, Long> {
  require(bps.values.all { it >= 0 } && sumsTo(bps.values.map { it.toLong() }, 10000L)) {
    "percent must sum to 10000bps"
  }
  return largestRemainder(total, bps.mapValues { it.value.toLong() }, 10000L)
}

fun shares(total: Long, weights: Map<Long, Long>): Map<Long, Long> {
  // A zero weight is a member owing nothing, not an error: shares() floors every
  // member, so a 2-cent bill split 1:2:3 stores {0, 1, 1}, and refusing that input
  // made a stored expense unrepresentable when the editor reopened it. Negatives
  // stay refused, so owed only ever moves toward the member.
  require(weights.isNotEmpty() && weights.values.all { it >= 0 }) { "weights must be non-negative" }
  return largestRemainder(
      total,
      weights,
      runCatching { checkedSum(weights.values) }
          .getOrElse { throw IllegalArgumentException("weights overflow") })
}

enum class TipMode {
  PROPORTIONAL,
  EQUAL
}

fun itemized(
    subtotals: Map<Long, Long>,
    total: Long,
    tipMinor: Long,
    taxMinor: Long,
    mode: TipMode
): Map<Long, Long> {
  require(subtotals.values.all { it >= 0 } && sumsTo(subtotals.values, total)) {
    "subtotals must sum to total"
  }
  require(tipMinor >= 0 && taxMinor >= 0)
  val extra =
      try {
        Math.addExact(tipMinor, taxMinor)
      } catch (_: ArithmeticException) {
        throw IllegalArgumentException("tip+tax overflow")
      }
  if (extra == 0L) return subtotals.toMap()
  val weights =
      when (mode) {
        TipMode.PROPORTIONAL ->
            if (checkedSum(subtotals.values) == 0L) subtotals.mapValues { 1L } else subtotals
        TipMode.EQUAL -> subtotals.mapValues { 1L }
      }
  val bonus = largestRemainder(extra, weights, checkedSum(weights.values))
  return subtotals.mapValues { (id, sub) ->
    try {
      Math.addExact(sub, bonus[id]!!)
    } catch (_: ArithmeticException) {
      throw IllegalArgumentException("itemized share overflow")
    }
  }
}

fun largestRemainder(total: Long, weights: Map<Long, Long>, denom: Long): Map<Long, Long> {
  require(
      total >= 0 &&
          weights.isNotEmpty() &&
          weights.values.all { it >= 0 } &&
          denom > 0 &&
          runCatching { checkedSum(weights.values) }.getOrNull() == denom) {
        "weights must be non-empty, non-negative and sum to denom"
      }
  val num =
      try {
        weights.mapValues { Math.multiplyExact(total, it.value) }
      } catch (_: ArithmeticException) {
        throw IllegalArgumentException("split overflows 64-bit")
      }
  val floors = num.mapValues { it.value / denom }
  var left = total - floors.values.sum()
  val order = weights.keys.sortedWith(compareByDescending<Long> { num[it]!! % denom }.thenBy { it })
  val out = floors.toMutableMap()
  var i = 0
  while (left-- > 0) {
    val k = order[i++ % order.size]
    out[k] = out[k]!! + 1
  }
  return out
}
