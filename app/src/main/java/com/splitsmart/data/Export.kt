package com.splitsmart.data

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.net.URI
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class GroupSer(
    val name: String,
    val currencyCode: String,
    val selfName: String?,
    val kind: String = "GROUP",
    val archived: Boolean = false,
    val color: Int = 0
)

@Serializable data class MemberSer(val name: String, val colorSeed: Int)

@Serializable
data class ExpenseSer(
    val payer: String,
    val amountMinor: Long,
    val currencyCode: String,
    val category: String,
    val note: String?,
    val dateEpoch: Long,
    val shares: Map<String, Long>,
    val payments: Map<String, Long> = mapOf(),
    val items: List<ItemSer> = listOf(),
    val receipt: String? = null,
    val receiptData: String? = null,
    val customCatId: Long? = null,
    val tipMinor: Long = 0L,
    val taxMinor: Long = 0L
)

@Serializable
data class ItemSer(val label: String, val amountMinor: Long)

/** Ids stay at zero on purpose: `saveExpense` renumbers the expense and its items when it inserts. */
fun ItemSer.toEntity() = ExpenseItem(expenseId = 0L, label = label, amountMinor = amountMinor)

/** Custom categories are global, so they live on the backup itself, stored by id so restore can
 * re-attach an expense to its category. */
@Serializable data class CustomCatSer(val id: Long, val name: String, val emoji: String)

@Serializable
data class SettlementSer(
    val from: String,
    val to: String,
    val amountMinor: Long,
    val dateEpoch: Long,
    val note: String?
)

@Serializable
data class TemplateSer(
    val payer: String,
    val amountMinor: Long,
    val category: String,
    val note: String?,
    val frequency: String,
    val nextDueEpoch: Long,
    val active: Boolean,
    val splitRule: String = ""
)

@Serializable data class SubgroupSer(val name: String, val members: List<String>)

/**
 * Rates, comments and the feed are as much a part of the group as its expenses, and a backup that
 * omits them restores a plausible wrong group: a foreign-currency expense with no rate falls back to
 * parity, so the balance is wrong rather than obviously broken. All three are keyed by name, since
 * the whole dump is, so a restore never carries a database id across.
 */
@Serializable
data class RateSer(
    val fromCode: String,
    val toCode: String,
    val rate: Double,
    val timeEpoch: Long
)

/**
 * A comment names its expense by [expenseIdx], its position in the dump's expense
 * list. A dump has no stable id for a row and two expenses can share a note and a
 * date, so position is the only handle a comment has.
 */
@Serializable
data class CommentSer(
    val author: String,
    val text: String,
    val timeEpoch: Long,
    val expenseIdx: Int
)

@Serializable data class EventSer(val kind: String, val summary: String, val timeEpoch: Long)

@Serializable
data class GroupDump(
    val group: GroupSer,
    val members: List<MemberSer>,
    val expenses: List<ExpenseSer>,
    val settlements: List<SettlementSer>,
    val templates: List<TemplateSer>,
    val subgroups: List<SubgroupSer> = listOf(),
    val rates: List<RateSer> = listOf(),
    val comments: List<CommentSer> = listOf(),
    val events: List<EventSer> = listOf()
)

@Serializable
data class Backup(
    val version: Int,
    val exportedAt: Long,
    val groups: List<GroupDump>,
    val customCategories: List<CustomCatSer> = listOf()
)

private const val MAX_RECEIPT_BYTES = 8 * 1024 * 1024

/**
 * Like [runCatching], but never swallows cancellation: the import and restore loops wrap each row so
 * one bad row does not abandon the rest, and `runCatching` would also catch the
 * CancellationException a cancelled coroutine throws, so a user who backed out mid-way would not
 * stop it and every remaining row would be reported as a bogus "skipped" entry.
 */
private inline fun <T> recoverable(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (c: CancellationException) {
      throw c
    } catch (t: Throwable) {
      Result.failure(t)
    }

/**
 * Backup, import and restore. Every entry point moves itself to [Dispatchers.IO]
 * rather than trusting the caller: these read whole files and base64 every receipt
 * into a single string, and the callers all run on a ViewModel's main dispatcher.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class Exporter(
    private val repo: SplitRepository,
    private val resolver: ContentResolver? = null,
    private val filesDir: File? = null
) {
  suspend fun dumpGroup(gid: Long): GroupDump = withContext(Dispatchers.IO) { dumpGroupOn(gid) }

  /**
   * The name each member is spelled with in the dump, keyed by member id.
   *
   * Every name in a dump -- payer, share, payment, settlement endpoint, subgroup roster, comment
   * author -- is a member's name and nothing else, which is what makes a restore replayable on
   * another device, and makes this the only place a collision can be resolved: keyed by name, two
   * members called "Alex" collapse into one and the shares stop summing to the amount. A collision
   * becomes "Alex (2)", first occurrence keeping the bare name, and the rule is the same on the
   * way back in.
   */
  private fun serialNames(ms: List<Member>): Map<Long, String> {
    val used = mutableSetOf<String>()
    return ms.associate { m -> m.id to disambiguate(m.name, used) }
  }

  private fun disambiguate(name: String, used: MutableSet<String>): String {
    if (used.add(name)) return name
    var k = 2
    while (!used.add("$name ($k)")) k++
    return "$name ($k)"
  }

  private suspend fun dumpGroupOn(gid: Long): GroupDump {
    val g = repo.group(gid).first()!!
    val ms = repo.members(gid).first()
    val nm = serialNames(ms)
    val ex =
        repo.expenses(gid).first().map { e ->
          ExpenseSer(
              nm[e.payerId] ?: "?",
              e.amountMinor,
              e.currencyCode,
              e.category.name,
              e.note,
              e.dateEpoch,
              repo.shares(e.id).associate { (nm[it.memberId] ?: "?") to it.owedMinor },
              repo.payments(e.id).associate { (nm[it.memberId] ?: "?") to it.paidMinor },
              repo.items(e.id).map { ItemSer(it.label, it.amountMinor) },
              e.receiptUri,
              e.receiptUri?.let { readReceiptBytes(it)?.let { Base64.encode(it) } },
              e.customCatId,
              e.tipMinor,
              e.taxMinor)
        }
    val st =
        repo.settlements(gid).first().map {
          SettlementSer(
              nm[it.fromId] ?: "?", nm[it.toId] ?: "?", it.amountMinor, it.dateEpoch, it.note)
        }
    val tp =
        repo.templates(gid).first().map {
          TemplateSer(
              nm[it.payerId] ?: "?",
              it.amountMinor,
              it.category.name,
              it.note,
              it.frequency.name,
              it.nextDueEpoch,
              it.active,
              it.splitRule)
        }
    val sg =
        repo.subgroups(gid).first().map { s ->
          SubgroupSer(
              s.name,
              recoverable { repo.subgroupMembers(s.id) }
                  .getOrDefault(emptyList())
                  .mapNotNull { nm[it] })
        }
    val cmt =
        repo.expenses(gid).first().flatMapIndexed { i, e ->
          repo.comments(e.id).first().map {
            CommentSer(nm[it.authorId] ?: "?", it.text, it.timeEpoch, i)
          }
        }
    val ev =
        repo.events(gid).first().map { EventSer(it.kind, it.summary, it.timeEpoch) }
    val rt =
        repo.rates(gid).first().map { RateSer(it.fromCode, it.toCode, it.rate, it.timeEpoch) }
    return GroupDump(
        GroupSer(
            g.name,
            g.currencyCode,
            ms.firstOrNull { it.id == g.selfMemberId }?.name,
            g.kind.name,
            g.archived,
            g.color),
        ms.map { MemberSer(nm[it.id] ?: it.name, it.colorSeed) },
        ex,
        st,
        tp,
        sg,
        rt,
        cmt,
        ev)
  }

  suspend fun backup(): String = withContext(Dispatchers.IO) { backupOn() }

  private suspend fun backupOn(): String {
    val dumps = repo.allGroups().first().map { dumpGroup(it.id) }
    val cats = repo.customCats().first().map { CustomCatSer(it.id, it.name, it.emoji) }
    return Json.encodeToString(
        Backup(
            version = 2,
            exportedAt = System.currentTimeMillis(),
            groups = dumps,
            customCategories = cats))
  }

  fun csv(d: GroupDump): String = buildString {
    append("﻿type,payer,amount_minor,currency,category,note,date,details\n")
    // Without a `self` row the import guesses the first payer in the file.
    d.group.selfName?.let { append("self,${cell(it)},,,,,,\n") }
    d.expenses.forEach { e ->
      val det =
          (e.shares.entries.map { "${esc(it.key)}=${it.value}" } +
                  e.payments.entries.map { "paid:${esc(it.key)}=${it.value}" } +
                  e.items.map { "item:${esc(it.label)}=${it.amountMinor}" })
              .joinToString(";")
      append(
          "expense,${cell(e.payer)},${e.amountMinor},${e.currencyCode},${e.category},${cell(e.note ?: "")},${e.dateEpoch},${cell(det)}\n")
    }
    d.settlements.forEach { s ->
      append(
          "settlement,${cell(s.from + " -> " + s.to)},${s.amountMinor},,,${cell(s.note ?: "")},${s.dateEpoch},\n")
    }
    // The roster, not the count: a count cannot rebuild one. A plain `;` list in the
    // details column, which only this row reads.
    d.subgroups.forEach { s ->
      append(
          "subgroup,${cell(s.name)},,,,,,${cell(s.members.joinToString(";") { esc(it) })}\n")
    }
  }

  /**
   * What an import actually did, including what it could not do. A row that fails
   * validation is skipped rather than aborting the import, so [skipped] is what
   * says "Imported 43" was not 43 of 50.
   */
  data class ImportResult(val imported: Int, val skipped: List<String>)

  suspend fun importCsv(name: String, currency: String, csv: String): ImportResult =
      withContext(Dispatchers.IO) { importCsvOn(name, currency, csv) }

  private suspend fun importCsvOn(name: String, currency: String, csv: String): ImportResult {
    val rows = parseCsvRows(csv)
    require(rows.isNotEmpty() && rows.first().firstOrNull() == "type") { "not a SplitSmart CSV" }
    val cur = currency.uppercase().take(3).ifBlank { "USD" }
    val now = System.currentTimeMillis()
    val gid =
        repo.saveGroup(
            Group(name = name.ifBlank { "Imported" }, currencyCode = cur, createdAt = now))
    val ids = mutableMapOf<String, Long>()
    suspend fun memberId(nm: String): Long =
        ids.getOrPut(nm) { repo.saveMember(Member(groupId = gid, name = nm, createdAt = now)) }
    var n = 0
    val skipped = mutableListOf<String>()
    var selfName: String? = null
    rows.drop(1).forEach { r ->
      recoverable {
        when (r.getOrNull(0)) {
          "self" -> {
            selfName = r.getOrNull(1)?.ifBlank { null }
            null
          }
          "expense" -> {
            require((r.getOrNull(3) ?: cur) == cur) { "currency mismatch" }
            val amount = (r.getOrNull(2) ?: "").toLong()
            require(amount > 0) { "bad amount" }
            // Parsed as strictly as the amount beside it: defaulting a missing date to
            // "now" would import a hand-edited row as dated today.
            val date = (r.getOrNull(6) ?: "").toLong()
            val pid = memberId(r.getOrNull(1) ?: "")
            val (shares, pays, items) = parseDetails(r.getOrNull(7) ?: "")
            val shareIds = (shares.keys + pays.keys).map { memberId(it) }.ifEmpty { listOf(pid) }
            repo.saveExpense(
                Expense(
                    groupId = gid,
                    payerId = pid,
                    amountMinor = amount,
                    currencyCode = cur,
                    category =
                        runCatching { Category.valueOf(r.getOrNull(4) ?: "") }
                            .getOrDefault(Category.OTHER),
                    note = r.getOrNull(5)?.ifBlank { null },
                    dateEpoch = date,
                    createdAt = now),
                shareIds,
                SplitInput(
                    SplitType.EXACT, shares.mapNotNull { memberId(it.key).to(it.value) }.toMap()),
                pays.mapNotNull { memberId(it.key).to(it.value) }.toMap().ifEmpty { null },
                items.map { it.toEntity() },
            )
            n++
          }
          "settlement" -> {
            val ends = (r.getOrNull(1) ?: "").split(" -> ", limit = 2)
            require(ends.size == 2) { "bad settlement" }
            val amount = (r.getOrNull(2) ?: "").toLong()
            require(amount > 0) { "bad amount" }
            // A settlement's date is when the money moved; the import date would
            // rewrite it.
            val date = (r.getOrNull(6) ?: "").toLong()
            repo.recordSettlement(
                Settlement(
                    groupId = gid,
                    fromId = memberId(ends[0]),
                    toId = memberId(ends[1]),
                    amountMinor = amount,
                    dateEpoch = date,
                    note = r.getOrNull(5)?.ifBlank { null }))
            n++
          }
          // A subgroup carries its roster in the row, and is not added to
          // `imported`, which counts money.
          "subgroup" -> {
            val name = r.getOrNull(1).orEmpty()
            require(name.isNotBlank()) { "subgroup has no name" }
            val members =
                splitKept(r.getOrNull(7) ?: "", ';')
                    .filter { it.isNotEmpty() }
                    .map { memberId(unesc(it)) }
            repo.saveSubgroup(gid, name, members)
          }
        }
      }
        .onFailure { skipped += "row ${r.getOrNull(1).orEmpty()}: ${it.message ?: it::class.simpleName}" }
    }
    // recordSettlement refuses a payment against a debt that does not exist, so a settlement
    // column that does not reconcile with the expenses can leave the member unset. A group
    // reporting a hardcoded zero is worse than a guess.
    if (selfName == null) selfName = ids.keys.firstOrNull()
    repo.saveGroup(repo.group(gid).first()!!.copy(selfMemberId = selfName?.let { ids[it] }))
    return ImportResult(n, skipped)
  }

  private fun parseCsvRows(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    var cur = StringBuilder()
    var q = false
    var i = 0
    val s = text.trimStart('\uFEFF')
    while (i < s.length) {
      val c = s[i]
      when {
        q && c == '"' && s.getOrNull(i + 1) == '"' -> {
          cur.append('"')
          i += 2
        }
        c == '"' -> {
          q = !q
          i++
        }
        !q && c == ',' -> {
          row.add(cur.toString())
          cur = StringBuilder()
          i++
        }
        !q && (c == '\n' || c == '\r') -> {
          row.add(cur.toString())
          cur = StringBuilder()
          if (row.any { it.isNotEmpty() }) rows.add(row)
          row = mutableListOf()
          if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
          i++
        }
        else -> {
          cur.append(c)
          i++
        }
      }
    }
    row.add(cur.toString())
    if (row.any { it.isNotEmpty() }) rows.add(row)
    return rows
  }

  private fun splitKept(s: String, delim: Char): List<String> {
    val out = mutableListOf<String>()
    val b = StringBuilder()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        b.append(c)
        b.append(s[i + 1])
        i += 2
      } else if (c == delim) {
        out.add(b.toString())
        b.clear()
        i++
      } else {
        b.append(c)
        i++
      }
    }
    out.add(b.toString())
    return out
  }

  private fun unesc(s: String): String {
    val b = StringBuilder()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        b.append(s[i + 1])
        i += 2
      } else {
        b.append(c)
        i++
      }
    }
    return b.toString()
  }

  private fun parseDetails(
      det: String
  ): Triple<Map<String, Long>, Map<String, Long>, List<ItemSer>> {
    val shares = mutableMapOf<String, Long>()
    val pays = mutableMapOf<String, Long>()
    val items = mutableListOf<ItemSer>()
    splitKept(det, ';')
        .filter { it.isNotEmpty() }
        .forEach { part ->
          when {
            part.startsWith("paid:") ->
                splitKept(part.removePrefix("paid:"), '=')
                    .takeIf { it.size == 2 }
                    ?.let { pays[unesc(it[0])] = it[1].toLong() }
            part.startsWith("item:") ->
                splitKept(part.removePrefix("item:"), '=')
                    .takeIf { it.size == 2 }
                    ?.let { items.add(ItemSer(unesc(it[0]), it[1].toLong())) }
            else ->
                splitKept(part, '=')
                    .takeIf { it.size == 2 }
                    ?.let { shares[unesc(it[0])] = it[1].toLong() }
          }
        }
    return Triple(shares, pays, items)
  }

  /**
   * What a restore actually did. A corrupt row is skipped rather than aborting the
   * whole restore, so [skipped] carries what was lost and the caller is expected to
   * surface it.
   */
  data class RestoreResult(
      val restored: Int,
      val skipped: List<String>,
      // Groups this backup carried that the database already held under the same name,
      // currency and member roster. Nothing is held back on account of this: restoring a
      // file onto a phone that still has the groups in it is a copy, not a skip, and two
      // designs that suppressed the duplicate were both rejected for breaking that. The
      // roster match is whole-equality, so a group that has gained a member since the
      // backup was taken does not match and its copy goes unreported.
      val duplicated: List<String> = emptyList())

  suspend fun restore(json: String): RestoreResult = withContext(Dispatchers.IO) { restoreOn(json) }

  private suspend fun restoreOn(json: String): RestoreResult {
    val b = Json.decodeFromString<Backup>(json)
    require(b.version in 1..2) { "unsupported backup version ${b.version}" }
    var n = 0
    val skipped = mutableListOf<String>()
    fun skip(what: String, why: Throwable) {
      skipped += "$what: ${why.message ?: why::class.simpleName}"
    }
    // Custom categories are global, so they run first: a name the app refuses is reported
    // with nothing half-written behind it.
    val catIds = mutableMapOf<Long, Long>()
    b.customCategories.forEach { c ->
      recoverable {
            val existing = repo.customCats().first().firstOrNull { it.name == c.name }
            catIds[c.id] =
                existing?.id ?: repo.saveCustomCat(CustomCategory(name = c.name, emoji = c.emoji))
          }
          .onFailure { skip("custom category \"${c.name.take(20)}\"", it) }
    }
    val duplicated = mutableListOf<String>()
    b.groups.forEach { d ->
      val grp =
          Group(
              name = d.group.name,
              currencyCode = d.group.currencyCode,
              kind =
                  runCatching { GroupKind.valueOf(d.group.kind) }.getOrDefault(GroupKind.GROUP),
              archived = d.group.archived,
              color = d.group.color,
              createdAt = System.currentTimeMillis())
      val members =
          d.members.map { ms ->
            Member(
                groupId = 0L,
                name = ms.name,
                colorSeed = ms.colorSeed,
                createdAt = grp.createdAt)
          }
      val roster = members.map { it.name }.sorted()
      if (repo.allGroups().first().any { existing ->
        existing.name == grp.name &&
            existing.currencyCode == grp.currencyCode &&
            repo.members(existing.id).first().map { it.name }.sorted() == roster
      }) {
        duplicated += d.group.name
      }
      val selfName = d.group.selfName
      val (gid, ids) =
          repo.createGroupWithMembers(grp, members) { created ->
            created[selfName] ?: created.values.firstOrNull()
          }
      d.subgroups.forEach { s ->
        recoverable { repo.saveSubgroup(gid, s.name, s.members.mapNotNull { ids[it] }) }
            .onFailure { skip("subgroup ${s.name}", it) }
      }
      val restoredIds = HashMap<Int, Long>()
      // The label is spelled out because a bare return@forEach inside forEachIndexed
      // binds to the enclosing groups loop.
      d.expenses.forEachIndexed exp@{ ei, e ->
        val pid = ids[e.payer]
        if (pid == null) {
          skipped += "expense \"${e.note.orEmpty()}\": payer ${e.payer} is not in this group"
          return@exp
        }
        // A `file:` uri resolves only on the machine that wrote it, so it is not
        // carried over; a non-`file:` uri names no path into this app's storage.
        val portable = e.receipt?.takeIf { !it.startsWith("file:") }
        val receipt = writeReceiptBytes(gid, e.receiptData) ?: portable
        if (receipt == null && (e.receiptData != null || e.receipt != null)) {
          skip(
              "receipt of expense \"${e.note.orEmpty()}\"",
              IllegalStateException("the image was not in the backup and is not on this device"))
        }
        recoverable {
          restoredIds[ei] =
              repo.saveExpense(
                  Expense(
                      groupId = gid,
                      payerId = pid,
                      amountMinor = e.amountMinor,
                      currencyCode = e.currencyCode,
                      category =
                          runCatching { Category.valueOf(e.category) }.getOrDefault(Category.OTHER),
                      note = e.note,
                      dateEpoch = e.dateEpoch,
                      receiptUri = receipt,
                      customCatId = e.customCatId?.let { catIds[it] },
                      tipMinor = e.tipMinor,
                      taxMinor = e.taxMinor,
                      createdAt = System.currentTimeMillis()),
                  e.shares.mapNotNull { ids[it.key] }.ifEmpty { e.payments.mapNotNull { ids[it.key] } },
                  SplitInput(
                      SplitType.EXACT, e.shares.mapNotNull { ids[it.key]?.to(it.value) }.toMap()),
                  e.payments.mapNotNull { ids[it.key]?.to(it.value) }.toMap().ifEmpty { null },
                  e.items.map { it.toEntity() },
              )
          n++
        }.onFailure { skip("expense \"${e.note.orEmpty()}\" (${e.currencyCode})", it) }
      }
      d.rates.forEach { r ->
        recoverable {
              repo.saveRate(
                  FxRate(
                      groupId = gid,
                      fromCode = r.fromCode,
                      toCode = r.toCode,
                      rate = r.rate,
                      timeEpoch = r.timeEpoch))
            }
            .onFailure { skip("rate ${r.fromCode}->${r.toCode}", it) }
      }
      d.settlements.forEach { s ->
        val f = ids[s.from]
        val t = ids[s.to]
        if (f == null || t == null) {
          skipped += "settlement ${s.from}->${s.to}: member is not in this group"
          return@forEach
        }
        recoverable {
          repo.recordSettlement(
              Settlement(
                  groupId = gid,
                  fromId = f,
                  toId = t,
                  amountMinor = s.amountMinor,
                  dateEpoch = s.dateEpoch,
                  note = s.note))
        }.onFailure { skip("settlement ${s.from}->${s.to}", it) }
      }
      d.templates.forEach { t ->
        val pid = ids[t.payer]
        if (pid == null) {
          skipped += "template \"${t.note.orEmpty()}\": payer ${t.payer} is not in this group"
          return@forEach
        }
        recoverable {
          val rule =
              t.splitRule.ifBlank { SplitRepository.encodeRule(SplitType.EQUAL, mapOf(), mapOf()) }
          repo.saveTemplate(
              RecurringTemplate(
                  groupId = gid,
                  payerId = pid,
                  amountMinor = t.amountMinor,
                  category =
                      runCatching { Category.valueOf(t.category) }.getOrDefault(Category.FOOD),
                  note = t.note,
                  splitRule = rule,
                  frequency =
                      runCatching { Frequency.valueOf(t.frequency) }
                          .getOrDefault(Frequency.MONTHLY),
                  nextDueEpoch = t.nextDueEpoch,
                  active = t.active))
        }.onFailure { skip("template \"${t.note.orEmpty()}\"", it) }
      }
      d.comments.forEach { c ->
        val eid = restoredIds[c.expenseIdx]
        if (eid == null) {
          skipped += "comment \"${c.text.take(20)}\": its expense was not restored"
          return@forEach
        }
        val author = ids[c.author] ?: ids.values.firstOrNull()
        if (author == null) {
          skipped += "comment \"${c.text.take(20)}\": its author was not restored"
          return@forEach
        }
        recoverable { repo.addComment(eid, author, c.text, c.timeEpoch) }
            .onFailure { skip("comment \"${c.text.take(20)}\"", it) }
      }
      d.events.forEach { ev ->
        recoverable { repo.replayEvent(gid, ev.kind, ev.summary, ev.timeEpoch) }
            .onFailure { skip("event ${ev.kind}", it) }
      }
    }
    return RestoreResult(n, skipped, duplicated)
  }

  private fun readCapped(stream: java.io.InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
      val n = stream.read(buf)
      if (n < 0) break
      total += n
      if (total > MAX_RECEIPT_BYTES) return null
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  private fun readReceiptBytes(uri: String): ByteArray? =
      runCatching {
            if (uri.startsWith("file:"))
                receiptFileFor(uri)
                    ?.takeIf { it.isFile && it.length() <= MAX_RECEIPT_BYTES }
                    ?.readBytes()
            else resolver?.openInputStream(Uri.parse(uri))?.use { readCapped(it) }
          }
          .getOrNull()

  private fun receiptFileFor(uri: String): File? {
    runCatching {
      return File(URI(uri))
    }
    return uri.removePrefix("file:").removePrefix("//").takeIf { it.isNotBlank() }?.let(::File)
  }

  private fun writeReceiptBytes(gid: Long, data: String?): String? {
    val dir = filesDir ?: return null
    // Pre-flight the encoded string, since decoding is what allocates. The decoded check
    // below is the one that decides.
    if ((data?.length ?: 0) > 4L * ((MAX_RECEIPT_BYTES + 2L) / 3L)) return null
    val bytes =
        data?.let {
          runCatching { Base64.decode(it) }.getOrNull()?.takeIf { it.size <= MAX_RECEIPT_BYTES }
        } ?: return null
    return runCatching {
          val out = restoredReceiptFile(dir, gid)
          out.writeBytes(bytes)
          out.toURI().toString()
        }
        .getOrNull()
  }

  private fun esc(s: String) =
      s.replace("\\", "\\\\").replace(";", "\\;").replace("=", "\\=").replace(":", "\\:")

  private fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
