package com.splitsmart.core.settle

/**
 * One payment, and the currency it is denominated in.
 *
 * Required, with no default, because two plans produce these. The simplified one
 * comes from the group's nets and is already in the group's own currency. The
 * unsimplified one is built per expense, so each row is in *that expense's*
 * currency, and a group may hold several. A field only one of the two fills in is
 * one the renderer has to guess at, and guessing wrong records a settlement in a
 * currency the debt was never in.
 */
data class Transfer(
    val from: Long,
    val to: Long,
    val amountMinor: Long,
    val currencyCode: String
) {
  init {
    require(from != to && amountMinor >= 1)
    require(currencyCode.isNotBlank()) { "a transfer must name its currency" }
  }
}

enum class Force {
  AUTO,
  EXACT,
  GREEDY
}

private fun sumsToZero(values: Collection<Long>): Boolean =
    try {
      var s = 0L
      values.forEach { s = Math.addExact(s, it) }
      s == 0L
    } catch (_: ArithmeticException) {
      false
    }

fun settle(
    nets: Map<Long, Long>,
    force: Force = Force.AUTO,
    currencyCode: String
): List<Transfer> {
  val nz = nets.filterValues { it != 0L }
  if (nz.isEmpty()) return emptyList()
  require(sumsToZero(nz.values)) { "nets must sum to zero" }
  return if (force == Force.GREEDY || (force == Force.AUTO && nz.size > 15))
      greedy(nz, currencyCode)
  else exact(nz, currencyCode)
}

fun greedy(nets: Map<Long, Long>, currencyCode: String): List<Transfer> {
  require(sumsToZero(nets.values)) { "nets must sum to zero" }
  val cred = nets.filterValues { it > 0 }.mapValues { it.value }.toMutableMap()
  val debt = nets.filterValues { it < 0 }.mapValues { -it.value }.toMutableMap()
  val out = mutableListOf<Transfer>()
  while (cred.isNotEmpty() && debt.isNotEmpty()) {
    val (c, ca) = cred.maxWithOrNull(compareBy<Map.Entry<Long, Long>>({ it.value }, { it.key }))!!
    val (d, da) = debt.maxWithOrNull(compareBy<Map.Entry<Long, Long>>({ it.value }, { it.key }))!!
    val x = minOf(ca, da)
    out += Transfer(d, c, x, currencyCode)
    if (ca == x) cred.remove(c) else cred[c] = ca - x
    if (da == x) debt.remove(d) else debt[d] = da - x
  }
  return out.sortedWith(compareBy({ it.from }, { it.to }, { it.currencyCode }, { it.amountMinor }))
}

fun exact(nets: Map<Long, Long>, currencyCode: String): List<Transfer> {
  require(sumsToZero(nets.values)) { "nets must sum to zero" }
  val nz = nets.filterValues { it != 0L }
  require(nz.size <= 15) { "exact() supports at most 15 nets; use settle() for larger inputs" }
  val ids = nz.keys.sorted()
  val bal = ids.map { nz[it]!! }
  val n = ids.size
  val sum = LongArray(1 shl n)
  // A subset that overflows Long cannot be a balanced group, whatever the wrapped
  // arithmetic says. The sentinel can never equal 0, so the search skips it rather
  // than the table reporting 0 and the caller settling an unbalanced group.
  val OVERFLOW = Long.MIN_VALUE
  for (m in 1 until (1 shl n)) {
    val b = m.countTrailingZeroBits()
    sum[m] =
        try {
          Math.addExact(sum[m xor (1 shl b)], bal[b])
        } catch (_: ArithmeticException) {
          OVERFLOW
        }
  }
  val dp = IntArray(1 shl n) { -1 }
  val pick = IntArray(1 shl n)
  dp[0] = 0
  for (m in 1 until (1 shl n)) {
    var s = m
    while (s > 0) {
      if (sum[s] == 0L && dp[m xor s] >= 0) {
        val v = dp[m xor s] + 1
        if (v > dp[m]) {
          dp[m] = v
          pick[m] = s
        }
      }
      s = (s - 1) and m
    }
  }
  val out = mutableListOf<Transfer>()
  var m = (1 shl n) - 1
  while (m != 0) {
    val g = pick[m].takeIf { it != 0 } ?: m
    require(sum[g] == 0L) { "nets must sum to zero" }
    val gb = (0 until n).filter { g and (1 shl it) != 0 }.associate { ids[it] to bal[it] }
    out += greedy(gb.filter { it.value != 0L }, currencyCode)
    m = m xor g
  }
  return out.sortedWith(compareBy({ it.from }, { it.to }, { it.currencyCode }, { it.amountMinor }))
}
