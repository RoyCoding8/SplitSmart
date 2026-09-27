package com.splitsmart.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
  @Query("SELECT * FROM `groups` WHERE archived = 0 ORDER BY createdAt DESC")
  fun groups(): Flow<List<Group>>

  @Query("SELECT * FROM `groups` ORDER BY createdAt DESC") fun groupsAll(): Flow<List<Group>>

  @Query("SELECT * FROM `groups` WHERE archived = 0 AND kind = :kind ORDER BY createdAt DESC")
  fun groupsByKind(kind: GroupKind): Flow<List<Group>>

  @Query("SELECT * FROM `groups` WHERE id = :id") fun group(id: Long): Flow<Group?>

  @Insert suspend fun insert(g: Group): Long

  @Update suspend fun update(g: Group)

  @Query("DELETE FROM `groups` WHERE id = :id") suspend fun delete(id: Long)

  @Query("SELECT COUNT(*) FROM `groups` WHERE selfMemberId = :id")
  suspend fun selfRefCount(id: Long): Int
}

@Dao
interface MemberDao {
  @Query("SELECT * FROM members WHERE groupId = :g ORDER BY createdAt")
  fun members(g: Long): Flow<List<Member>>

  @Query("SELECT * FROM members WHERE settleNudge = 1") suspend fun nudged(): List<Member>

  @Query("UPDATE members SET settleNudge = :v WHERE id = :id")
  suspend fun setNudge(id: Long, v: Boolean)

  @Query("SELECT * FROM members WHERE id = :id") suspend fun byId(id: Long): Member?

  @Insert suspend fun insert(m: Member): Long

  @Update suspend fun update(m: Member)

  @Query("DELETE FROM members WHERE id = :id") suspend fun delete(id: Long)

  @Query(
      "SELECT COUNT(*) FROM expenses WHERE payerId = :id OR id IN (SELECT expenseId FROM shares WHERE memberId = :id) OR id IN (SELECT expenseId FROM payments WHERE memberId = :id)")
  suspend fun refCount(id: Long): Int
}

@Dao
interface ExpenseDao {
  @Query("SELECT * FROM expenses WHERE groupId = :g ORDER BY dateEpoch DESC, id DESC")
  fun expenses(g: Long): Flow<List<Expense>>

  @Query("SELECT * FROM expenses ORDER BY dateEpoch DESC, id DESC")
  fun allExpenses(): Flow<List<Expense>>

  @Query("SELECT * FROM expenses WHERE groupId = :g ORDER BY dateEpoch DESC, id DESC LIMIT :n")
  fun recent(g: Long, n: Int): Flow<List<Expense>>

  @Query("SELECT * FROM expenses WHERE id = :id") suspend fun byId(id: Long): Expense?

  @Insert suspend fun insert(e: Expense): Long

  @Update suspend fun update(e: Expense)

  @Query("DELETE FROM expenses WHERE id = :id") suspend fun delete(id: Long)

  @Query("SELECT * FROM shares WHERE expenseId = :e")
  suspend fun shares(e: Long): List<ExpenseShare>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putShares(s: List<ExpenseShare>)

  @Query("DELETE FROM shares WHERE expenseId = :e") suspend fun clearShares(e: Long)

  @Query("SELECT * FROM payments WHERE expenseId = :e")
  suspend fun payments(e: Long): List<ExpensePayment>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPayments(p: List<ExpensePayment>)

  @Query("DELETE FROM payments WHERE expenseId = :e") suspend fun clearPayments(e: Long)

  @Query("SELECT * FROM items WHERE expenseId = :e ORDER BY id")
  suspend fun items(e: Long): List<ExpenseItem>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putItems(i: List<ExpenseItem>)

  @Query("DELETE FROM items WHERE expenseId = :e") suspend fun clearItems(e: Long)

  /** Returns the id the child rows were written under, which is the caller's own when the expense
   * already had one and the only value the caller can read the bundle back with. */
  @Transaction
  suspend fun replaceFull(
      e: Expense,
      s: List<ExpenseShare>,
      p: List<ExpensePayment>,
      items: List<ExpenseItem>
  ): Long {
    val id =
        if (e.id == 0L) insert(e)
        else {
          update(e)
          e.id
        }
    clearShares(id)
    clearPayments(id)
    clearItems(id)
    putShares(s.map { it.copy(expenseId = id) })
    putPayments(p.map { it.copy(expenseId = id) })
    putItems(items.map { it.copy(expenseId = id) })
    return id
  }

  @Query("SELECT * FROM settlements WHERE groupId = :g ORDER BY dateEpoch DESC, id DESC")
  fun settlements(g: Long): Flow<List<Settlement>>

  @Insert suspend fun insertSettlement(s: Settlement): Long

  @Insert suspend fun insertSettlements(s: List<Settlement>)

  @Transaction
  suspend fun insertFull(
      e: Expense,
      s: List<ExpenseShare>,
      p: List<ExpensePayment>,
      items: List<ExpenseItem>
  ): Long {
    val id = insert(e.copy(id = 0))
    putShares(s.map { it.copy(expenseId = id) })
    putPayments(p.map { it.copy(expenseId = id) })
    putItems(items.map { it.copy(id = 0, expenseId = id) })
    return id
  }

  @Query("SELECT COUNT(*) FROM expenses WHERE recurringId = :t AND dateEpoch = :d")
  suspend fun dupeCount(t: Long, d: Long): Int

  @Query("SELECT COUNT(*) FROM settlements WHERE fromId = :id OR toId = :id")
  suspend fun settleRefCount(id: Long): Int

  @Query("SELECT COUNT(*) FROM templates WHERE payerId = :id")
  suspend fun templateRefCount(id: Long): Int

  @Query("SELECT COUNT(*) FROM expenses WHERE customCatId = :id")
  suspend fun customCatRefCount(id: Long): Int

  @Query("SELECT * FROM templates WHERE groupId = :g ORDER BY nextDueEpoch")
  fun templates(g: Long): Flow<List<RecurringTemplate>>

  @Insert suspend fun insertTemplate(t: RecurringTemplate): Long

  @Update suspend fun updateTemplate(t: RecurringTemplate)

  @Query("DELETE FROM templates WHERE id = :id") suspend fun deleteTemplate(id: Long)
}

@Dao
interface SubgroupDao {
  @Query("SELECT * FROM subgroups WHERE groupId = :g ORDER BY id")
  fun subgroups(g: Long): Flow<List<Subgroup>>

  @Query("SELECT * FROM subgroups WHERE id = :s") suspend fun subgroupById(s: Long): Subgroup?

  @Query("SELECT memberId FROM subgroup_members WHERE subgroupId = :s")
  suspend fun subgroupMemberIds(s: Long): List<Long>

  @Insert suspend fun insertSubgroup(s: Subgroup): Long

  @Update suspend fun updateSubgroup(s: Subgroup)

  @Query("DELETE FROM subgroups WHERE id = :id") suspend fun deleteSubgroup(id: Long)

  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addSubgroupMember(m: SubgroupMember)

  @Query("DELETE FROM subgroup_members WHERE subgroupId = :s")
  suspend fun clearSubgroupMembers(s: Long)

  @Transaction
  suspend fun setSubgroupMembers(s: Long, ids: List<Long>) {
    clearSubgroupMembers(s)
    ids.forEach { addSubgroupMember(SubgroupMember(s, it)) }
  }

  @Query("SELECT COUNT(*) FROM subgroup_members WHERE memberId = :id")
  suspend fun memberRefCount(id: Long): Int
}

@Dao
interface EventDao {
  @Query("SELECT * FROM events WHERE groupId = :g ORDER BY timeEpoch DESC, id DESC")
  fun eventsByGroup(g: Long): Flow<List<Event>>

  @Insert suspend fun insert(e: Event): Long
}

@Dao
interface SearchDao {
  @Query(
      "SELECT e.* FROM expenses e JOIN expense_fts ON e.id = expense_fts.rowid WHERE expense_fts MATCH :q ORDER BY e.dateEpoch DESC, e.id DESC LIMIT :n")
  fun searchExpenses(q: String, n: Int): Flow<List<Expense>>

  @Query(
      "SELECT m.* FROM members m JOIN member_fts ON m.id = member_fts.rowid WHERE member_fts MATCH :q ORDER BY m.name LIMIT :n")
  fun searchMembers(q: String, n: Int): Flow<List<Member>>

  @Query(
      "SELECT * FROM `groups` WHERE name LIKE '%' || :frag || '%' ESCAPE '\\' ORDER BY createdAt DESC LIMIT :n")
  fun searchGroups(frag: String, n: Int): Flow<List<Group>>
}

@Dao
interface CustomCatDao {
  @Query("SELECT * FROM custom_categories ORDER BY name COLLATE NOCASE")
  fun all(): Flow<List<CustomCategory>>

  @Insert suspend fun insert(c: CustomCategory): Long

  @Update suspend fun update(c: CustomCategory)

  @Query("DELETE FROM custom_categories WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface FxDao {
  @Query("SELECT * FROM fx_rates WHERE groupId = :g ORDER BY timeEpoch DESC, id DESC")
  fun rates(g: Long): Flow<List<FxRate>>

  @Insert suspend fun insert(r: FxRate): Long

  @Update suspend fun update(r: FxRate)

  @Query("DELETE FROM fx_rates WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface CommentDao {
  @Query("SELECT * FROM comments WHERE expenseId = :e ORDER BY timeEpoch, id")
  fun commentsFor(e: Long): Flow<List<Comment>>

  @Insert suspend fun insert(c: Comment): Long

  @Query("SELECT COUNT(*) FROM comments WHERE authorId = :id")
  suspend fun authorRefCount(id: Long): Int

  @Query("DELETE FROM comments WHERE id = :id") suspend fun delete(id: Long)
}
