package com.splitsmart.data

/**
 * The two shapes almost every data-layer test builds: a group with some named members, and
 * one equal-split expense in the group currency. Named [newGroup] members rather than
 * positional so a group whose name matters to the assertion reads
 * `newGroup(at, "Ann", "Bo", name = "Trip")` and not a swapped pair.
 */

/** A fresh group holding exactly [members], in the order named, plus their ids. */
internal suspend fun SplitRepository.newGroup(
    at: Long,
    vararg members: String,
    name: String = "T"
): Pair<Long, List<Long>> {
  val g = saveGroup(Group(name = name, currencyCode = "USD", createdAt = at))
  return g to members.map { saveMember(Member(groupId = g, name = it, createdAt = at)) }
}

/**
 * One equal-split expense. [participants] is who is in the split, which is not
 * always who is in the group, and [at] dates the expense and stamps its creation
 * in one go, so a test cannot quietly date the two differently.
 */
internal suspend fun SplitRepository.charge(
    gid: Long,
    payer: Long,
    amountMinor: Long,
    participants: List<Long>,
    at: Long,
    category: Category = Category.FOOD,
    currencyCode: String = "USD",
    note: String? = null,
    receiptUri: String? = null,
    customCatId: Long? = null
): Long =
    saveExpense(
        Expense(
            groupId = gid,
            payerId = payer,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            category = category,
            note = note,
            receiptUri = receiptUri,
            customCatId = customCatId,
            dateEpoch = at,
            createdAt = at),
        participants,
        SplitInput(SplitType.EQUAL))
