package com.splitsmart.data

/**
 * The group these tests keep rebuilding: a group, its members by name, and a handful of
 * equal-split bills.
 *
 * Money is Long minor units, and `pickRate` picks the rate at or before the expense's own
 * date, so a bill is dated at [DbTest.now] and a rate stamped before it.
 */
object MoneyTest {

  /** A group whose members are named once here and referred to by id everywhere after. */
  suspend fun members(
      repo: SplitRepository,
      now: Long,
      names: List<String>,
      currencyCode: String = "USD",
      name: String = "T"
  ): Pair<Long, List<Long>> {
    val g = repo.saveGroup(Group(name = name, currencyCode = currencyCode, createdAt = now))
    return g to names.map { repo.saveMember(Member(groupId = g, name = it, createdAt = now)) }
  }

  /**
   * An equal bill in [currencyCode] that [payerId] fronted, split [participants] ways.
   *
   * Participants are passed rather than assumed to be the whole group, because the
   * subgroups and multi-payer tests are about a bill that is not the group's shape.
   * `saveExpense` checks [payments] against the total, so it cannot carry a map that
   * does not balance.
   */
  suspend fun bill(
      repo: SplitRepository,
      now: Long,
      groupId: Long,
      payerId: Long,
      participants: List<Long>,
      amountMinor: Long,
      currencyCode: String,
      note: String? = null,
      atEpoch: Long = now,
      payments: Map<Long, Long>? = null
  ): Long =
      repo.saveExpense(
          Expense(
              groupId = groupId,
              payerId = payerId,
              amountMinor = amountMinor,
              currencyCode = currencyCode,
              category = Category.FOOD,
              note = note,
              dateEpoch = atEpoch,
              createdAt = atEpoch),
          participants,
          SplitInput(SplitType.EQUAL),
          payments,
      )

  /** 1 unit of [from] in [to], stamped [ageMillis] before [now], the default being a second. */
  suspend fun rate(
      repo: SplitRepository,
      groupId: Long,
      now: Long,
      from: String,
      to: String,
      value: Double,
      ageMillis: Long = 1000L
  ): Long =
      repo.saveRate(
          FxRate(
              groupId = groupId,
              fromCode = from,
              toCode = to,
              rate = value,
              timeEpoch = now - ageMillis))
}
