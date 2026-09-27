package com.splitsmart.data

import androidx.room.*

enum class Category {
  FOOD,
  GROCERIES,
  RENT,
  UTILITIES,
  TRANSPORT,
  TRAVEL,
  ENTERTAINMENT,
  HEALTH,
  SHOPPING,
  OTHER
}

enum class Frequency {
  DAILY,
  WEEKLY,
  MONTHLY
}

enum class SplitType {
  EQUAL,
  EXACT,
  PERCENT,
  SHARES
}

enum class GroupKind {
  GROUP,
  FRIEND
}

@Entity(tableName = "groups")
data class Group(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val currencyCode: String,
    val selfMemberId: Long? = null,
    @ColumnInfo(defaultValue = "'GROUP'") val kind: GroupKind = GroupKind.GROUP,
    val createdAt: Long,
    val archived: Boolean = false,
    val color: Int = 0,
)

@Entity(
    tableName = "members",
    foreignKeys = [ForeignKey(Group::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")])
data class Member(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val name: String,
    val colorSeed: Int = 0,
    val createdAt: Long,
    val settleNudge: Boolean = false,
    val avatarPath: String? = null,
)

@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(Group::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    // dupeCount -- the only guard against generating the same recurring occurrence
    // twice -- filters on recurringId alone, so it needs its own index.
    indices =
        [
          Index("groupId"),
          Index(value = ["groupId", "dateEpoch"]),
          Index("recurringId")
        ],
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val payerId: Long,
    val amountMinor: Long,
    val currencyCode: String,
    val category: Category,
    val note: String? = null,
    val dateEpoch: Long,
    val recurringId: Long? = null,
    val receiptUri: String? = null,
    val createdAt: Long,
    val customCatId: Long? = null,
    /**
     * How this expense was split, so reopening it in the editor does not re-derive the split
     * from the shares and quietly turn a percent or shares split into an equal one. EQUAL is
     * the default because equal is the only split the shares always could have been.
     */
    val splitType: SplitType = SplitType.EQUAL,
    /**
     * The tip and tax an itemized bill was saved with, in minor units. The participants'
     * subtotals and one total that already has tip and tax folded in are what get stored, so
     * without this the breakdown is unrecoverable -- and empty Tip and Tax fields look like
     * values never entered rather than values the app failed to load. Zero before these
     * columns existed, which is right: their totals are the subtotals.
     */
    val tipMinor: Long = 0L,
    val taxMinor: Long = 0L,
)

@Entity(
    tableName = "shares",
    primaryKeys = ["expenseId", "memberId"],
    foreignKeys =
        [ForeignKey(Expense::class, ["id"], ["expenseId"], onDelete = ForeignKey.CASCADE)],
)
data class ExpenseShare(val expenseId: Long, val memberId: Long, val owedMinor: Long)

@Entity(
    tableName = "payments",
    primaryKeys = ["expenseId", "memberId"],
    foreignKeys =
        [ForeignKey(Expense::class, ["id"], ["expenseId"], onDelete = ForeignKey.CASCADE)],
)
data class ExpensePayment(val expenseId: Long, val memberId: Long, val paidMinor: Long)

@Entity(
    tableName = "items",
    foreignKeys =
        [ForeignKey(Expense::class, ["id"], ["expenseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("expenseId")],
)
data class ExpenseItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val label: String,
    val amountMinor: Long,
)

@Entity(
    tableName = "settlements",
    foreignKeys = [ForeignKey(Group::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")],
)
data class Settlement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val fromId: Long,
    val toId: Long,
    val amountMinor: Long,
    val dateEpoch: Long,
    val note: String? = null,
)

@Entity(
    tableName = "templates",
    foreignKeys = [ForeignKey(Group::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")])
data class RecurringTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val payerId: Long,
    val amountMinor: Long,
    val category: Category,
    val note: String? = null,
    val splitRule: String,
    val frequency: Frequency,
    val nextDueEpoch: Long,
    val active: Boolean = true,
)

@Entity(
    tableName = "subgroups",
    foreignKeys = [ForeignKey(Group::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")],
)
data class Subgroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val name: String,
)

@Entity(
    tableName = "subgroup_members",
    primaryKeys = ["subgroupId", "memberId"],
    indices = [Index("memberId")],
    foreignKeys =
        [
            ForeignKey(Subgroup::class, ["id"], ["subgroupId"], onDelete = ForeignKey.CASCADE),
            ForeignKey(Member::class, ["id"], ["memberId"], onDelete = ForeignKey.CASCADE),
        ],
)
data class SubgroupMember(val subgroupId: Long, val memberId: Long)

object EventKind {
  const val EXPENSE_ADDED = "EXPENSE_ADDED"
  const val EXPENSE_UPDATED = "EXPENSE_UPDATED"
  const val EXPENSE_DELETED = "EXPENSE_DELETED"
  const val EXPENSE_RESTORED = "EXPENSE_RESTORED"
  const val SETTLEMENT = "SETTLEMENT"
  const val MEMBER_ADDED = "MEMBER_ADDED"
  const val MEMBER_REMOVED = "MEMBER_REMOVED"
  const val COMMENT_ADDED = "COMMENT_ADDED"
  const val RATE_SET = "RATE_SET"
}

@Entity(
    tableName = "comments",
    foreignKeys =
        [ForeignKey(Expense::class, ["id"], ["expenseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("expenseId")],
)
data class Comment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val authorId: Long,
    val text: String,
    val timeEpoch: Long,
)

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(Group::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId"), Index(value = ["timeEpoch", "id"])],
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val timeEpoch: Long,
    val kind: String,
    val summary: String,
)

@Fts4(contentEntity = Expense::class)
@Entity(tableName = "expense_fts")
data class ExpenseFts(val note: String?, val category: String)

@Fts4(contentEntity = Member::class)
@Entity(tableName = "member_fts")
data class MemberFts(val name: String)

@Entity(tableName = "custom_categories")
data class CustomCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
)

@Entity(
    tableName = "fx_rates",
    foreignKeys = [ForeignKey(Group::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId"), Index(value = ["groupId", "fromCode", "toCode", "timeEpoch"])],
)
data class FxRate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val fromCode: String,
    val toCode: String,
    val rate: Double,
    val timeEpoch: Long,
)
