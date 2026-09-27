package com.splitsmart.data

import android.util.Log
import com.splitsmart.core.settle.Transfer
import com.splitsmart.core.settle.greedy
import com.splitsmart.core.settle.settle
import com.splitsmart.core.splits.*
import androidx.room.withTransaction
import com.splitsmart.ui.vm.money
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class SplitInput(
    val type: SplitType,
    val values: Map<Long, Long> = mapOf(),
    val bps: Map<Long, Int> = mapOf()
)

data class SearchHits(
    val expenses: List<Expense>,
    val members: List<Member>,
    val groups: List<Group>
)

/**
 * An outstanding balance worth reminding someone about.
 *
 * [unpriced] is the missing-rate signal [com.splitsmart.ui.vm.DashRow] and
 * [com.splitsmart.ui.vm.FriendTotals] carry, and it matters more here: this text leaves the app as
 * a notification, so a caller that cannot see what it does not know has nothing to check
 * [netMinor] against.
 */
data class Nudge(
    val memberName: String,
    val groupName: String,
    val netMinor: Long,
    val currencyCode: String,
    val unpriced: List<String> = emptyList()
)

fun ftsQuery(raw: String): String? =
    raw.split(Regex("\\s+"))
        .mapNotNull { t -> t.filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() } }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ") { "$it*" }

fun likeFrag(raw: String): String =
    raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** One message for one rule, so the single-row and bulk settle paths cannot tell the user two
 * different things about the same group. */
private fun unpricedSettleRefusal(unpriced: List<String>, cur: String): String =
    "no rate entered for ${unpriced.joinToString(" and ")}, so there is nothing to settle in " +
        if (cur.isBlank()) "this group's currency" else cur

data class ExpenseSnapshot(
    val expense: Expense,
    val shares: List<ExpenseShare>,
    val payments: List<ExpensePayment>,
    val items: List<ExpenseItem>,
    /** The expense's comments, which the delete cascades away with it. Part of what undo restores. */
    val comments: List<Comment> = emptyList()
)

data class ExpenseBundle(
    val expense: Expense,
    val shares: Map<Long, Long>,
    val payments: Map<Long, Long>,
    val items: List<ExpenseItem>
)

open class SplitRepository(private val db: AppDatabase) {
  private val g = db.groups()
  private val m = db.members()
  private val e = db.expenses()
  private val friendMutex = Mutex()

  fun groups() = g.groups()

  fun allGroups() = g.groupsAll()

  fun groupsByKind(kind: GroupKind) = db.groups().groupsByKind(kind)

  fun group(id: Long) = g.group(id)

  suspend fun friendGroup(friendName: String, currency: String): Long {
    val nm = friendName.trim()
    require(nm.isNotEmpty())
    // Read and create under one lock, over every group: archived is a view filter, not a
    // deletion, so a lookup that skipped archived groups created a second one for a
    // friend who was merely archived.
    return friendMutex.withLock {
      allGroups()
          .first()
          .firstOrNull { it.kind == GroupKind.FRIEND && it.name.equals(nm, true) }
          ?.let { return@withLock it.id }
      val at = System.currentTimeMillis()
      // All four writes or none: the lookup above returns any FRIEND group matching the
      // name, so a half-built one would be handed back on every later attempt.
      db.withTransaction {
        val gid =
            g.insert(
                Group(
                    name = nm,
                    currencyCode = currency.uppercase(),
                    kind = GroupKind.FRIEND,
                    createdAt = at))
        val me = m.insert(Member(groupId = gid, name = "Me", createdAt = at))
        m.insert(Member(groupId = gid, name = nm, createdAt = at))
        g.update(
            Group(
                id = gid,
                name = nm,
                currencyCode = currency.uppercase(),
                selfMemberId = me,
                kind = GroupKind.FRIEND,
                createdAt = at))
        gid
      }
    }
  }

  /**
   * Creates a group with its members and its "you" in one transaction, and returns the new group's
   * id together with the member ids it created, keyed by name.
   *
   * A group is only usable once it has a [Group.selfMemberId]: every balance surface reads it, and a
   * group without one reports a hardcoded zero however much is owed. [resolveSelf] is given the ids
   * created here, keyed by name, and names which of them is "you"; null is a legitimate answer and
   * leaves the group without a self.
   *
   * Returns a pair because the caller needs those member ids for everything it writes after this,
   * and looking them up again would be a second source of truth for who is who.
   */
  suspend fun createGroupWithMembers(
      gr: Group,
      members: List<Member>,
      resolveSelf: (ids: Map<String, Long>) -> Long?
  ): Pair<Long, Map<String, Long>> =
      db.withTransaction {
        val gid = g.insert(gr.copy(selfMemberId = null))
        val ids = mutableMapOf<String, Long>()
        members.forEach { ids[it.name] = m.insert(it.copy(groupId = gid, id = 0L)) }
        val self = resolveSelf(ids)
        g.update(gr.copy(id = gid, selfMemberId = self))
        gid to ids.toMap()
      }

  suspend fun saveGroup(gr: Group) =
      if (gr.id == 0L) g.insert(gr)
      else {
        g.update(gr)
        gr.id
      }

  suspend fun deleteGroup(id: Long) = g.delete(id)

  fun members(gid: Long) = m.members(gid)

  suspend fun member(id: Long) = m.byId(id)

  fun events(gid: Long) = db.events().eventsByGroup(gid)

  /**
   * Writes a feed entry that already exists, rather than logging a new action. A restore is
   * replaying a past, so it needs the original timestamp and summary kept intact.
   */
  suspend fun replayEvent(gid: Long, kind: String, summary: String, at: Long) {
    db.events().insert(Event(groupId = gid, timeEpoch = at, kind = kind, summary = summary))
  }

  private suspend fun log(groupId: Long, kind: String, summary: String) {
    db.events()
        .insert(
            Event(
                groupId = groupId,
                timeEpoch = System.currentTimeMillis(),
                kind = kind,
                summary = summary))
  }

  private suspend fun names(gid: Long) = m.members(gid).first().associate { it.id to it.name }

  private fun label(ex: Expense) =
      ex.note?.takeIf { it.isNotBlank() }
          ?: ex.category.name.lowercase().replaceFirstChar { it.uppercase() }

  suspend fun saveMember(mm: Member) =
      if (mm.id == 0L) {
        val id = m.insert(mm)
        log(mm.groupId, EventKind.MEMBER_ADDED, "${mm.name.trim()} joined the group")
        id
      } else {
        m.update(mm)
        mm.id
      }

  /**
   * Removes a member, and only when nothing refers to them.
   *
   * The census is what makes this safe and the transaction is what makes the census true.
   * `shares.memberId` and `payments.memberId` carry no foreign key to `members`, so a
   * `saveExpense` that read the member list just before this ran can commit a share for a row
   * that no longer exists -- and the member is then gone with a ledger reference that cannot be
   * cleaned through the UI.
   */
  suspend fun deleteMember(id: Long) {
    val mm = m.byId(id) ?: return
    db.withTransaction {
      require(netsNative(mm.groupId)[id]?.values?.all { it == 0L } ?: true) {
        "member still has a balance; settle up first"
      }
      require(m.refCount(id) == 0) { "member has ledger references" }
      require(e.settleRefCount(id) == 0) { "member has settlement references" }
      require(e.templateRefCount(id) == 0) { "member pays for a recurring template" }
      require(db.subgroups().memberRefCount(id) == 0) { "member belongs to a subgroup" }
      require(g.selfRefCount(id) == 0) { "member is a group's self identity" }
      require(db.comments().authorRefCount(id) == 0) { "member wrote a comment" }
      m.delete(id)
      log(mm.groupId, EventKind.MEMBER_REMOVED, "${mm.name} left the group")
    }
  }

  fun rates(gid: Long) = db.rates().rates(gid)

  suspend fun saveRate(r: FxRate): Long {
    val f = r.fromCode.uppercase()
    val t = r.toCode.uppercase()
    require(f.length == 3 && t.length == 3 && f != t) {
      "rate needs two different 3-letter currencies"
    }
    require(r.rate.isFinite() && r.rate > 0) { "rate must be positive" }
    require(g.group(r.groupId).first() != null) { "unknown group" }
    val norm = r.copy(fromCode = f, toCode = t)
    val id =
        if (r.id == 0L) {
          db.rates().insert(norm)
        } else {
          db.rates().update(norm)
          r.id
        }
    log(r.groupId, EventKind.RATE_SET, "Rate set: ${rateLine(norm)}")
    return id
  }

  suspend fun deleteRate(id: Long) = db.rates().delete(id)

  fun customCats() = db.customCats().all()

  suspend fun saveCustomCat(c: CustomCategory): Long {
    val name = c.name.trim()
    require(name.isNotEmpty() && name.length <= 40) { "category needs a name (max 40)" }
    val emoji = c.emoji.ifBlank { "🏷️" }
    return if (c.id == 0L) db.customCats().insert(c.copy(name = name, emoji = emoji))
    else {
      db.customCats().update(c.copy(name = name, emoji = emoji))
      c.id
    }
  }

  suspend fun deleteCustomCat(id: Long) {
    require(e.customCatRefCount(id) == 0) { "category is used by expenses" }
    db.customCats().delete(id)
  }

  fun expenses(gid: Long) = e.expenses(gid)

  fun allExpenses() = e.allExpenses()

  fun search(raw: String, n: Int = 50): Flow<SearchHits> {
    val q = ftsQuery(raw) ?: return flowOf(SearchHits(emptyList(), emptyList(), emptyList()))
    val s = db.search()
    return combine(
        s.searchExpenses(q, n),
        s.searchMembers(q, n),
        s.searchGroups(likeFrag(raw), n),
        ::SearchHits)
  }

  fun recent(gid: Long, n: Int) = e.recent(gid, n)

  fun settlements(gid: Long) = e.settlements(gid)

  fun templates(gid: Long) = e.templates(gid)

  suspend fun shares(eid: Long) = e.shares(eid)

  suspend fun saveExpense(
      ex: Expense,
      participants: List<Long>,
      input: SplitInput,
      payments: Map<Long, Long>? = null,
      items: List<ExpenseItem> = emptyList()
  ): Long {
    require(ex.amountMinor > 0 && participants.isNotEmpty())
    require(g.group(ex.groupId).first() != null) { "unknown group" }
    val cur = ex.currencyCode.uppercase()
    require(cur.length == 3) { "expense needs a 3-letter currency" }
    val ids = m.members(ex.groupId).first().map { it.id }.toSet()
    require(ex.payerId in ids && participants.all { it in ids }) { "unknown member" }
    val pays = payments ?: mapOf(ex.payerId to ex.amountMinor)
    require(pays.values.all { it > 0 } && pays.keys.all { it in ids }) { "invalid payments" }
    require(sumsTo(pays.values, ex.amountMinor)) { "payments must sum to total" }
    val shares = calcShares(ex.amountMinor, participants, input)
    require(sumsTo(shares.values, ex.amountMinor))
    val isNew = ex.id == 0L
    // Recorded here rather than inferred on the way out: the shares alone cannot say how they
    // were arrived at, so a 60/20/20 percent split and a hand-typed exact split produce the
    // same owed amounts.
    val norm = ex.copy(currencyCode = cur, splitType = input.type)
    val id =
        e.replaceFull(
            norm,
            shares.map { ExpenseShare(norm.id, it.key, it.value) },
            pays.map { ExpensePayment(norm.id, it.key, it.value) },
            items)
    val nm = names(ex.groupId)
    val desc = "${nm[ex.payerId] ?: "Someone"} paid ${money(ex.amountMinor, cur)} for ${label(ex)}"
    log(
        ex.groupId,
        if (isNew) EventKind.EXPENSE_ADDED else EventKind.EXPENSE_UPDATED,
        if (isNew) desc else "$desc (edited)")
    return id
  }

  suspend fun payments(eid: Long) = e.payments(eid)

  suspend fun items(eid: Long) = e.items(eid)

  fun subgroups(gid: Long) = db.subgroups().subgroups(gid)

  suspend fun subgroupMembers(sid: Long) = db.subgroups().subgroupMemberIds(sid)

  /**
   * Creates a subgroup, or renames and re-members an existing one when [sid] is
   * given.
   */
  suspend fun saveSubgroup(gid: Long, name: String, ids: List<Long>, sid: Long = 0): Long {
    val members = m.members(gid).first().map { it.id }.toSet()
    require(name.isNotBlank() && ids.isNotEmpty() && ids.all { it in members }) {
      "invalid subgroup"
    }
    if (sid != 0L)
        require(db.subgroups().subgroupById(sid)?.groupId == gid) {
          "subgroup belongs to another group"
        }
    val id =
        if (sid == 0L) db.subgroups().insertSubgroup(Subgroup(groupId = gid, name = name.trim()))
        else {
          db.subgroups().updateSubgroup(Subgroup(id = sid, groupId = gid, name = name.trim()))
          sid
        }
    db.subgroups().setSubgroupMembers(id, ids.distinct())
    return id
  }

  suspend fun deleteSubgroup(id: Long) = db.subgroups().deleteSubgroup(id)

  suspend fun expenseBundle(eid: Long): ExpenseBundle? {
    val ex = e.byId(eid) ?: return null
    return ExpenseBundle(
        ex,
        e.shares(eid).associate { it.memberId to it.owedMinor },
        e.payments(eid).associate { it.memberId to it.paidMinor },
        e.items(eid))
  }

  /**
   * Who actually fronted the money for this expense, in the expense's own currency. An expense with
   * no payment rows means nobody split the bill, so the payer owes the whole amount. The single
   * definition of that rule: `computeNets`, `netsNative` and `rawTransfers` all read through here.
   */
  private suspend fun paidBy(ex: Expense): Map<Long, Long> {
    val pays = e.payments(ex.id)
    return if (pays.isEmpty()) mapOf(ex.payerId to ex.amountMinor)
    else pays.associate { it.memberId to it.paidMinor }
  }

  suspend fun deleteExpenseWithSnapshot(id: Long): ExpenseSnapshot {
    val ex = e.byId(id) ?: throw IllegalArgumentException("unknown expense")
    // Read the comments before the delete: they cascade with the expense.
    val comments = db.comments().commentsFor(id).first()
    val snap = ExpenseSnapshot(ex, e.shares(id), e.payments(id), e.items(id), comments)
    e.delete(id)
    log(
        ex.groupId,
        EventKind.EXPENSE_DELETED,
        "Deleted ${label(ex)} (${money(ex.amountMinor, ex.currencyCode)})")
    return snap
  }

  suspend fun restoreSnapshot(s: ExpenseSnapshot) {
    g.group(s.expense.groupId).first() ?: throw IllegalArgumentException("unknown group")
    require(s.expense.currencyCode.uppercase().length == 3) { "snapshot needs a 3-letter currency" }
    require(s.expense.amountMinor > 0) { "invalid snapshot amount" }
    require(sumsTo(s.shares.map { it.owedMinor }, s.expense.amountMinor)) {
      "snapshot shares do not sum to total"
    }
    require(sumsTo(s.payments.map { it.paidMinor }, s.expense.amountMinor)) {
      "snapshot payments do not sum to total"
    }
    val ids = m.members(s.expense.groupId).first().map { it.id }.toSet()
    require((s.shares.map { it.memberId } + s.payments.map { it.memberId }).all { it in ids }) {
      "snapshot names unknown member"
    }
    require(s.expense.payerId in ids) { "snapshot names unknown payer" }
    // The restored expense gets a fresh id, so the comments have to be re-attached to that
    // one. A comment whose author has since left the group is skipped rather than failing
    // the whole undo.
    val restoredId = e.insertFull(s.expense, s.shares, s.payments, s.items)
    s.comments.forEach { c ->
      if (c.authorId in ids) addComment(restoredId, c.authorId, c.text)
    }
    log(
        s.expense.groupId,
        EventKind.EXPENSE_RESTORED,
        "Restored ${label(s.expense)} (${money(s.expense.amountMinor, s.expense.currencyCode)})")
  }

  fun comments(eid: Long) = db.comments().commentsFor(eid)

  /**
   * [atEpoch] is when the comment was written, and it is a parameter rather than a clock read here
   * because a restore has to write the time the comment was actually made.
   */
  suspend fun addComment(
      expenseId: Long,
      authorId: Long,
      text: String,
      atEpoch: Long = System.currentTimeMillis()
  ): Long {
    val t = text.trim()
    require(t.isNotEmpty()) { "empty comment" }
    val ex = e.byId(expenseId) ?: throw IllegalArgumentException("unknown expense")
    val nm = names(ex.groupId)
    require(authorId in nm) { "unknown member" }
    val id =
        db.comments()
            .insert(
                Comment(
                    expenseId = expenseId,
                    authorId = authorId,
                    text = t,
                    timeEpoch = atEpoch))
    log(
        ex.groupId,
        EventKind.COMMENT_ADDED,
        "${nm[authorId] ?: "Someone"} commented on ${label(ex)}")
    return id
  }

  suspend fun deleteComment(id: Long) {
    db.comments().delete(id)
  }

  /**
   * Records a payment between two members.
   *
   * The `from` member must currently owe `to`, and the payment must not exceed that debt. Without
   * the first check a settlement is just a number written into the ledger. Without the second, a
   * member owing 1000 can post a 5000 payment and the balance is not settled but inverted, and
   * every settle-up row built from that wants *them* to pay.
   *
   * Both bounds are read through direct suspend queries rather than nets() because the check is on
   * the write path: it has to see the ledger as it is at this instant, not as a collector last
   * happened to observe it. The net is read inside the same transaction that inserts, which is the
   * only way the bound is true rather than merely current -- two taps both passing both checks
   * against the same pre-insert state turn a 1000 debt into 2000, and `net < 0` below then refuses
   * every later payment from that member with an error only deleting the payment clears.
   * [settleMutex] is what orders this against [settleAll], which settles the same debts from the
   * same nets.
   */
  suspend fun recordSettlement(s: Settlement) {
    require(s.fromId != s.toId && s.amountMinor > 0) { "invalid settlement" }
    settleMutex.withLock {
      db.withTransaction {
        val members = m.members(s.groupId).first()
        val ids = members.map { it.id }.toSet()
        require(s.fromId in ids && s.toId in ids) { "unknown member" }
        // A member's net is positive when the group owes them and negative when they owe the
        // group, so a payer can only start a settlement while their net is below zero. `-net`
        // cannot overflow: negating moves the value toward zero.
        val cur = g.group(s.groupId).first()?.currencyCode ?: ""
        val exps = e.expenses(s.groupId).first()
        val rs = rates(s.groupId).first()
        val net = computeNets(exps, settlements(s.groupId).first(), cur, rs)[s.fromId] ?: 0L
        require(net < 0) { "this member no longer owes anything" }
        // The debt above is a sum of converted expenses, and `pickRate` answers 1.0 for a pair
        // nobody entered a rate for. So the bound the payment is checked against is itself a
        // parity figure, and recording one writes an invented amount into the ledger as a debt
        // the group agreed to -- after which every later balance for this pair rests on it. The
        // bulk path [settleAll] refuses the same group for the same reason.
        val unpriced = unconvertibleCurrencies(exps, cur, rs)
        require(unpriced.isEmpty()) { unpricedSettleRefusal(unpriced, cur) }
        require(s.amountMinor <= -net) { "this payment is more than the ${money(-net, cur)} owed" }
        e.insertSettlement(s)
        val nm = members.associate { it.id to it.name }
        log(
            s.groupId,
            EventKind.SETTLEMENT,
            "${nm[s.fromId] ?: "?"} paid ${nm[s.toId] ?: "?"} ${money(s.amountMinor, cur)}")
      }
    }
  }

  private val settleMutex = Mutex()

  /**
   * Records every payment the simplified plan implies, or none of them. Refuses the same unpriced
   * group [recordSettlement] refuses. Under the same mutex, since [settleAll] settles the same
   * debts from the same nets.
   */
  suspend fun settleAll(gid: Long): Int =
      settleMutex.withLock {
        val plan = plan(gid, true)
        if (plan.isEmpty()) return@withLock 0
        val grp = g.group(gid).first()
        val cur = grp?.currencyCode ?: ""
        val rates = rates(gid).first()
        val now = System.currentTimeMillis()
        val unpriced = unconvertibleCurrencies(expenses(gid).first(), cur, rates)
        if (unpriced.isNotEmpty()) throw IllegalArgumentException(unpricedSettleRefusal(unpriced, cur))
        e.insertSettlements(
            plan.map {
              Settlement(
                  groupId = gid,
                  fromId = it.from,
                  toId = it.to,
                  amountMinor = it.amountMinor,
                  dateEpoch = now)
            })
        val nm = names(gid)
        plan.forEach {
          log(
              gid,
              EventKind.SETTLEMENT,
              "${nm[it.from] ?: "?"} paid ${nm[it.to] ?: "?"} ${money(it.amountMinor, cur)}")
        }
        return@withLock plan.size
      }

  suspend fun shareSummary(gid: Long): String {
    val grp = g.group(gid).first()
    val cur = grp?.currencyCode ?: ""
    val nm = m.members(gid).first().associate { it.id to it.name }
    val exps = expenses(gid).first()
    val rs = rates(gid).first()
    val plan = plan(gid, true)
    // This text leaves the app, so a total resting on a fabricated rate is withheld rather
    // than qualified: someone else will act on the number.
    val unpriced = unconvertibleCurrencies(exps, cur, rs)
    return buildString {
          appendLine("SplitSmart summary for ${grp?.name ?: "Group"}")
          val note = unpricedNote(unpriced)
          appendLine(
              if (note.isEmpty()) "Total spent ${money(totalIn(exps, cur, rs), cur)} across ${exps.size} expenses"
              else "$note, so no total is given across ${exps.size} expenses")
          if (plan.isEmpty()) appendLine("All settled. Nobody owes anything.")
          else
              plan.forEach {
                appendLine(
                    "${nm[it.from] ?: "?"} pays ${nm[it.to] ?: "?"} ${money(it.amountMinor, cur)}")
              }
          exps.take(10).forEach {
            appendLine(
                "· ${it.note ?: it.category.name}: ${money(it.amountMinor, it.currencyCode)} (paid by ${nm[it.payerId] ?: "?"})")
          }
        }
        .trimEnd()
  }

  suspend fun saveTemplate(t: RecurringTemplate): Long {
    require(g.group(t.groupId).first() != null) { "unknown group" }
    require(t.amountMinor > 0) { "template amount must be positive" }
    val ids = m.members(t.groupId).first().map { it.id }.toSet()
    require(decodeRule(t.splitRule, ids) != null) { "template split rule is undecodable" }
    return if (t.id == 0L) e.insertTemplate(t)
    else {
      e.updateTemplate(t)
      t.id
    }
  }

  suspend fun deleteTemplate(id: Long) = e.deleteTemplate(id)

  suspend fun generateAllDue(nowEpoch: Long = System.currentTimeMillis()): Int =
      allGroups().first().filter { !it.archived }.sumOf { generateDue(it.id, nowEpoch) }

  suspend fun setNudge(id: Long, v: Boolean) = m.setNudge(id, v)

  suspend fun nudgedOutstanding(): List<Nudge> =
      m.nudged().mapNotNull { mb ->
        val net = nets(mb.groupId).first()[mb.id] ?: 0L
        if (net == 0L) null
        else {
          val grp = g.group(mb.groupId).first()
          val cur = grp?.currencyCode ?: ""
          Nudge(
              mb.name,
              grp?.name ?: "",
              net,
              cur,
              unconvertibleCurrencies(
                  expenses(mb.groupId).first(), cur, rates(mb.groupId).first()))
        }
      }

  private val dueMutex = Mutex()

  suspend fun generateDue(gid: Long, nowEpoch: Long = System.currentTimeMillis()): Int =
      dueMutex.withLock {
        val grp = g.group(gid).first() ?: return@withLock 0
        val ids = m.members(gid).first().map { it.id }.toSet()
        var made = 0
        e.templates(gid)
            .first()
            .filter { it.active && it.nextDueEpoch <= nowEpoch }
            .forEach { t ->
              if (t.payerId !in ids || t.amountMinor <= 0) return@forEach
              val input = decodeRule(t.splitRule, ids, strict = true) ?: return@forEach
              var due = t.nextDueEpoch
              var step = 0
              var guard = 0
              var live = true
              val anchorDay = Instant.ofEpochMilli(t.nextDueEpoch).atZone(ZoneOffset.UTC).dayOfMonth
              while (due <= nowEpoch && guard++ < MAX_CATCHUP && live) {
                try {
                  if (e.dupeCount(t.id, due) == 0) {
                    val parts =
                        (input.values.keys + input.bps.keys)
                            .ifEmpty { ids.toList() }
                            .filter { it in ids }
                            .ifEmpty { ids.toList() }
                    saveExpense(
                        Expense(
                            groupId = gid,
                            payerId = t.payerId,
                            amountMinor = t.amountMinor,
                            currencyCode = grp.currencyCode,
                            category = t.category,
                            note = t.note,
                            dateEpoch = due,
                            recurringId = t.id,
                            createdAt = nowEpoch),
                        parts,
                        input,
                    )
                    made++
                  }
                  due = stepAdvance(t.nextDueEpoch, ++step, anchorDay, t.frequency)
                } catch (err: Exception) {
                  if (err is kotlinx.coroutines.CancellationException) throw err
                  // One ungeneratable occurrence stops this template's catch-up, so a bad
                  // split rule cannot spin through MAX_CATCHUP every 12 hours. Named `err`
                  // because `e` is the expense DAO.
                  Log.w("SplitSmart", "recurring template ${t.id} failed at $due", err)
                  live = false
                }
              }
              if (due != t.nextDueEpoch) e.updateTemplate(t.copy(nextDueEpoch = due))
            }
        return@withLock made
      }

  fun nets(gid: Long): Flow<Map<Long, Long>> =
      combine(expenses(gid), settlements(gid), group(gid), rates(gid)) { exps, settles, grp, rs ->
        computeNets(exps, settles, grp?.currencyCode ?: "", rs)
      }

  suspend fun plan(gid: Long, simplified: Boolean): List<Transfer> {
    val grp = g.group(gid).first()
    val nz =
        computeNets(
                expenses(gid).first(),
                settlements(gid).first(),
                grp?.currencyCode ?: "",
                rates(gid).first())
            .filterValues { it != 0L }
    if (nz.isEmpty()) return emptyList()
    return if (simplified) settle(nz, currencyCode = grp?.currencyCode ?: "")
    else rawTransfers(gid)
  }

  /**
   * Converts same-currency amounts at [rate] without losing the property that made them a split in
   * the first place: that they sum to the converted total.
   *
   * Rounding each amount on its own does not preserve that. At rate 1.1 the amounts 334/333/333
   * each convert and round to 367/366/366, summing to 1099, while the total 1000 converts to 1100.
   * `settle` requires the nets to sum to exactly zero, so that one unit of drift leaves the group
   * unsettleable, and a settlement only ever adds +x/-x, so nothing the user does restores it.
   *
   * So the total is converted once and the parts are apportioned from it by largest remainder,
   * which spends the rounding a single time. Both the shares and the payments go through here,
   * which is what makes an expense's own contribution to the nets exactly zero rather than
   * zero-plus-drift.
   */
  private fun apportionMinor(amounts: Map<Long, Long>, rate: Double): Map<Long, Long> {
    if (amounts.isEmpty()) return emptyMap()
    if (rate == 1.0) return amounts
    val total = checkedSum(amounts.values)
    if (total <= 0L) return amounts.mapValues { convertMinor(it.value, rate) }
    return largestRemainder(convertMinor(total, rate), amounts, total)
  }

  private suspend fun computeNets(
      exps: List<Expense>,
      settles: List<Settlement>,
      base: String,
      rates: List<FxRate>
  ): Map<Long, Long> {
    val nets = mutableMapOf<Long, Long>()
    // Every add below is checked: this map is the user's balance, and a wrapped one is silently
    // wrong by 2^64 rather than obviously broken.
    fun add(id: Long, delta: Long) {
      if (delta == 0L) return
      nets[id] = Math.addExact(nets[id] ?: 0L, delta)
    }
    exps.forEach { ex ->
      val rate = pickRate(rates, ex.currencyCode, base, ex.dateEpoch)
      val sharesByMember = e.shares(ex.id).associate { it.memberId to it.owedMinor }
      if (rate == 1.0 || sharesByMember.isEmpty()) {
        paidBy(ex).forEach { (id, paid) -> add(id, convertMinor(paid, rate)) }
        sharesByMember.forEach { (id, owed) -> add(id, -owed) }
        return@forEach
      }
      apportionMinor(sharesByMember, rate).forEach { (id, owed) -> add(id, -owed) }
      apportionMinor(paidBy(ex), rate).forEach { (id, paid) -> add(id, paid) }
    }
    settles.forEach {
      add(it.fromId, it.amountMinor)
      add(it.toId, -it.amountMinor)
    }
    return nets
  }

  /**
   * Per-member balances in native currencies (Splitwise `balance[]` parity): no conversion, so
   * foreign expenses never distort. Settlements count in the group's currency.
   */
  suspend fun netsNative(gid: Long): Map<Long, Map<String, Long>> {
    val base = g.group(gid).first()?.currencyCode ?: return emptyMap()
    val nets = mutableMapOf<Long, MutableMap<String, Long>>()
    fun add(id: Long, cur: String, amt: Long) {
      if (amt == 0L) return
      val per = nets.getOrPut(id) { mutableMapOf() }
      // Checked: this is a balance, and a wrapped one reads as a plausible small number.
      val v = Math.addExact(per[cur] ?: 0L, amt)
      if (v == 0L) per.remove(cur) else per[cur] = v
    }
    expenses(gid).first().forEach { ex ->
      paidBy(ex).forEach { (id, paid) -> add(id, ex.currencyCode, paid) }
      e.shares(ex.id).forEach { s -> add(s.memberId, ex.currencyCode, -s.owedMinor) }
    }
    settlements(gid).first().forEach {
      add(it.fromId, base, it.amountMinor)
      add(it.toId, base, -it.amountMinor)
    }
    return nets.mapValues { it.value.toMap() }.filterValues { it.isNotEmpty() }
  }

  private suspend fun rawTransfers(gid: Long): List<Transfer> {
    // Per expense, so a member only ever owes what they owe for the bills they were in, and the
    // list is the set of obligations rather than a net figure. But greedy() runs independently per
    // expense, so the same pair comes out of more than one of them, and those are one debt:
    // aggregating here rather than left as repeated rows, which the settle-up list keys by pair
    // and Compose rejects as a duplicate key.
    //
    // The key carries the currency as well as the pair, and that is not a refinement of the same
    // idea. A 100 EUR meal and a 100 USD meal both split between Alice and Bob produce the same
    // pair owing 50 each, and summing those into one 100 row names no currency at all. Two
    // expenses in two currencies are two debts, not one.
    val byPair = LinkedHashMap<Triple<Long, Long, String>, Long>()
    expenses(gid).first().forEach { ex ->
      val paid = paidBy(ex)
      val owed = e.shares(ex.id).associate { it.memberId to it.owedMinor }
      val nets =
          (paid.keys + owed.keys)
              .associateWith { (paid[it] ?: 0L) - (owed[it] ?: 0L) }
              .filterValues { it != 0L }
      if (nets.isNotEmpty()) greedy(nets, ex.currencyCode).forEach { t ->
        val k = Triple(t.from, t.to, t.currencyCode)
        val total = byPair[k] ?: 0L
        byPair[k] = Math.addExact(total, t.amountMinor)
      }
    }
    return byPair.entries
        .map { (key, amount) -> Transfer(key.first, key.second, amount, key.third) }
        .sortedWith(compareBy({ it.from }, { it.to }, { it.currencyCode }, { it.amountMinor }))
  }

  companion object {
    fun calcShares(total: Long, participants: List<Long>, input: SplitInput): Map<Long, Long> {
      require(participants.isNotEmpty())
      return when (input.type) {
        SplitType.EQUAL -> equal(total, participants)
        SplitType.EXACT -> exact(total, input.values.filterKeys { it in participants })
        SplitType.PERCENT -> percent(total, input.bps.filterKeys { it in participants })
        SplitType.SHARES -> shares(total, input.values.filterKeys { it in participants })
      }
    }

    fun encodeRule(type: SplitType, values: Map<Long, Long>, bps: Map<Long, Int>) =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
              put("type", type.name)
              put("v", buildJsonObject { values.forEach { (k, v) -> put(k.toString(), v) } })
              put("b", buildJsonObject { bps.forEach { (k, v) -> put(k.toString(), v) } })
            })

    const val MAX_CATCHUP = 12

    /**
     * Reads a stored split rule, or null when it will not parse.
     *
     * A rule names members by id, so ids outside [ids] are stale. Lenient drops them, so a rule
     * that is partly stale still reads as the parts that are not; [strict] rejects the whole rule,
     * which is what [generateDue] needs -- re-splitting a bill across whoever is left after a
     * member was deleted is a different bill, not a corrected one.
     */
    fun decodeRule(raw: String, ids: Set<Long>, strict: Boolean = false): SplitInput? =
        runCatching {
              val obj = Json.parseToJsonElement(raw).jsonObject
              fun keep(id: Long) = strict || id in ids
              val v =
                  obj["v"]
                      ?.jsonObject
                      ?.mapNotNull { (k, el) ->
                        k.toLongOrNull()?.takeIf(::keep)?.to(el.jsonPrimitive.long)
                      }
                      ?.toMap() ?: mapOf()
              val b =
                  obj["b"]
                      ?.jsonObject
                      ?.mapNotNull { (k, el) ->
                        k.toLongOrNull()?.takeIf(::keep)?.to(el.jsonPrimitive.int)
                      }
                      ?.toMap() ?: mapOf()
              if (strict && (v.keys + b.keys).any { it !in ids }) null
              else SplitInput(SplitType.valueOf(obj["type"]?.jsonPrimitive?.content ?: "EQUAL"), v, b)
            }
            .getOrNull()

    fun stepAdvance(start: Long, step: Int, anchorDay: Int, f: Frequency): Long {
      val z = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC)
      return when (f) {
        Frequency.DAILY -> z.plusDays(step.toLong()).toInstant().toEpochMilli()
        Frequency.WEEKLY -> z.plusWeeks(step.toLong()).toInstant().toEpochMilli()
        Frequency.MONTHLY ->
            z.plusMonths(step.toLong())
                .let { it.withDayOfMonth(minOf(anchorDay, it.toLocalDate().lengthOfMonth())) }
                .toInstant()
                .toEpochMilli()
      }
    }
  }
}
