package com.splitsmart.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitsmart.core.settle.Transfer
import com.splitsmart.data.*
import com.splitsmart.widget.ShortcutHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * One currency's worth of what the user is owed and owes, across every group.
 *
 * [unpriced] is the currencies behind these figures that no rate can convert into
 * [currency], so a screen holding a row can say the number rests on no rate.
 */
data class DashRow(
    val currency: String,
    val owed: Long,
    val owe: Long,
    val unpriced: List<String> = emptyList()
)

/** Rolls each group's net into one row per currency. */
fun summarizeDashboard(
    selfNets: List<Pair<String, Long>>,
    unpricedByCurrency: Map<String, List<String>> = emptyMap()
): List<DashRow> =
    selfNets
        .filter { it.second != 0L }
        .groupBy({ it.first }, { it.second })
        .map { (c, ns) ->
          DashRow(
              c,
              ns.filter { it > 0 }.sum(),
              -ns.filter { it < 0 }.sum(),
              unpricedByCurrency[c].orEmpty().distinct().sorted())
        }
        .sortedBy { it.currency }

/**
 * The undo bar on screen and the snapshot it stands for, as one value.
 *
 * A data class rather than a bare string so two deletes of the same expense are
 * still two offers; the value feeds `distinctUntilChanged`, so a repeated delete
 * of the same row is the one case that does not re-show the bar.
 */
data class UndoOffer(val snapshot: ExpenseSnapshot, val message: String)

/**
 * What a friend screen knows about a two-person group's spending, in [target].
 *
 * [unpriced] is the currencies behind these figures that no rate can convert into
 * [target]. Both are sums of converted expenses, so they are not wrong so much as
 * unknown, and a caller that cannot see what it does not know has nothing to
 * check the number against and prints it.
 */
data class FriendTotals(
    val paidBySelf: Long,
    val paidByFriend: Long,
    val count: Int,
    val unpriced: List<String> = emptyList()
)

fun friendTotalsIn(
    expenses: List<Expense>,
    selfId: Long?,
    target: String,
    rates: List<FxRate>
): FriendTotals =
    FriendTotals(
        expenses
            .filter { selfId != null && it.payerId == selfId }
            .sumOf {
              convertMinor(it.amountMinor, pickRate(rates, it.currencyCode, target, it.dateEpoch))
            },
        expenses
            .filter { selfId == null || it.payerId != selfId }
            .sumOf {
              convertMinor(it.amountMinor, pickRate(rates, it.currencyCode, target, it.dateEpoch))
            },
        expenses.size,
        unconvertibleCurrencies(expenses, target, rates),
    )

fun settleTriple(net: Long, selfId: Long, friendId: Long): Triple<Long, Long, Long>? =
    when {
      net > 0 -> Triple(friendId, selfId, net)
      net < 0 -> Triple(selfId, friendId, -net)
      else -> null
    }

/** One line of the shared settle-up text. [cur] is per row, not per plan. */
fun paybackLine(from: String, to: String, amountMinor: Long, cur: String) =
    "$from pays $to ${money(amountMinor, cur)}"

fun settleShareText(groupName: String, plan: List<Transfer>, names: Map<Long, String>): String =
    buildString {
          appendLine("Settle up for $groupName")
          plan.forEach {
            appendLine(
                paybackLine(
                    names[it.from] ?: "?", names[it.to] ?: "?", it.amountMinor, it.currencyCode))
          }
        }
        .trimEnd()

/**
 * The amount a settlement for this row records, in the group's currency.
 *
 * A settlement stores a bare `amountMinor` and the nets add it straight into the
 * group-currency ledger, so what is recorded is read as group-currency money
 * whatever the user typed. A plan row is in the currency of the expense it came
 * from, so it has to be restated here.
 */
fun settlementAmountIn(
    t: Transfer,
    groupCur: String,
    rates: List<FxRate>,
    atEpoch: Long
): Long = convertMinor(t.amountMinor, pickRate(rates, t.currencyCode, groupCur, atEpoch))

/**
 * The minor-unit digits, without a currency label: "12.50", never "12.5" and
 * never "12.500000000000001".
 *
 * Integer division, not a Double: a Double renders Long.MIN_VALUE as a different
 * number than the ledger holds. Also a payment-app URL parameter, so two decimals
 * is a contract, not cosmetics.
 */
fun minorToDecimal(amountMinor: Long): String {
  val neg = amountMinor < 0
  return (if (neg) "-" else "") + Math.abs(amountMinor / 100) + "." +
      Math.abs(amountMinor % 100).toString().padStart(2, '0')
}

private fun formEncode(s: String): String =
    try {
      java.net.URLEncoder.encode(s, "UTF-8")
    } catch (_: Exception) {
      s
    }

fun venmoUri(recipient: String, amountMinor: Long, note: String): String =
    "venmo://paycharge?txn=pay&recipients=${formEncode(recipient)}&amount=${minorToDecimal(amountMinor)}&note=${formEncode(note)}"

const val PAYPAL_LANDING = "https://www.paypal.com/paypalme/"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeVm @Inject constructor(private val repo: SplitRepository) : ViewModel() {
  private fun loads(src: Flow<List<Group>>): StateFlow<Load<List<Group>>> =
      src.map<_, Load<List<Group>>> { Load.Ok(it) }
          .catch { emit(Load.Err("load failed")) }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Load.Busy)

  val groups: StateFlow<Load<List<Group>>> = loads(repo.groups())

  /**
   * The dashboard, carrying which of its currencies rest on a rate nobody entered.
   *
   * A card summarizes groups the user is not looking at, so a parity figure here
   * is read as fact with nothing to check it against.
   */
  val dashboard: StateFlow<Load<List<DashRow>>> =
      repo
          .groups()
          .flatMapLatest { gs ->
            if (gs.isEmpty()) flowOf<Load<List<DashRow>>>(Load.Ok(emptyList()))
            else
                combine(
                    gs.map { g ->
                      combine(
                          repo.nets(g.id),
                          repo.expenses(g.id),
                          repo.rates(g.id)) { n, exps, rs ->
                        Triple(
                            g.currencyCode,
                            g.selfMemberId?.let { n[it] } ?: 0L,
                            unconvertibleCurrencies(exps, g.currencyCode, rs))
                      }
                    }) { arr ->
                      Load.Ok(
                          summarizeDashboard(
                              arr.map { it.first to it.second },
                              // A row sums every group behind it, so one group
                              // resting on parity leaves the whole row unpriced.
                              arr.filter { it.third.isNotEmpty() }
                                  .groupBy({ it.first }, { it.third })
                                  .mapValues { (_, v) -> v.flatten() }))
                    }
          }
          .catch { emit(Load.Err("load failed")) }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Load.Busy)

  val friendGroups = loads(repo.groupsByKind(GroupKind.FRIEND))
  val tripGroups = loads(repo.groupsByKind(GroupKind.GROUP))
  val archivedGroups = loads(repo.allGroups().map { gs -> gs.filter { it.archived } })

  suspend fun addFriend(name: String, currency: String): Long = repo.friendGroup(name, currency)

  fun unarchive(g: Group) = viewModelScope.launch { repo.saveGroup(g.copy(archived = false)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupDetailVm
@Inject
constructor(
    private val repo: SplitRepository,
    private val prefs: SettingsPrefsStore,
    @ApplicationContext private val app: Context
) : ViewModel() {
  private val gid = MutableStateFlow(-1L)
  private val simp = MutableStateFlow(true)

  fun bind(id: Long) {
    gid.value = id
  }

  fun toggle(s: Boolean) {
    simp.value = s
  }

  /** Whether the plan is simplified. The one storage for it. */
  val simplified: StateFlow<Boolean> = simp

  private fun <T> watching(initial: T, src: (Long) -> Flow<T>): StateFlow<T> =
      gid.flatMapLatest(src).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

  val group = watching(null) { repo.group(it) }
  val members = watching(emptyList<Member>()) { repo.members(it) }
  val expenses = watching(emptyList<Expense>()) { repo.expenses(it) }
  val settles = watching(emptyList<Settlement>()) { repo.settlements(it) }
  val feed = watching(emptyList<Event>()) { repo.events(it) }

  fun comments(eid: Long): Flow<List<Comment>> = repo.comments(eid)

  /**
   * Posts the comment, or says why it could not. Suspend returning the failure
   * rather than launching: `viewModelScope` has no handler, so a throw from a
   * discarded coroutine takes the process down.
   */
  suspend fun addComment(eid: Long, authorId: Long, text: String): String? =
      runCatching { repo.addComment(eid, authorId, text) }
          .exceptionOrNull()
          ?.message

  fun deleteComment(id: Long) = viewModelScope.launch { repo.deleteComment(id) }

  val nets: StateFlow<Map<Long, Long>> = watching(emptyMap()) { repo.nets(it) }
  val rates: StateFlow<List<FxRate>> = watching(emptyList()) { repo.rates(it) }

  /**
   * The plan is derived from expenses, settlements and rates, so all three
   * invalidate it. Combined purely as triggers; the plan is recomputed from the
   * repository each time.
   */
  val plan: StateFlow<List<Transfer>> =
      combine(gid, simp, expenses, settles, rates) { id, s, _, _, _ -> id to s }
          .flatMapLatest { (id, s) -> flow { emit(repo.plan(id, s)) } }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  // `rates` triggers this despite no conversion here: the members it names change
  // when a rate is added, and the plan and the native rows sit on the same card.
  val netsNative: StateFlow<Map<Long, Map<String, Long>>> =
      combine(gid, expenses, settles, rates) { id, _, _, _ -> id }
          .flatMapLatest { id -> flow { emit(if (id < 0) emptyMap() else repo.netsNative(id)) } }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

  suspend fun removeMember(id: Long): String? =
      runCatching { withContext(NonCancellable) { repo.deleteMember(id) } }
          .exceptionOrNull()
          ?.message

  suspend fun saveRate(r: FxRate): String? =
      runCatching { withContext(NonCancellable) { repo.saveRate(r.copy(groupId = gid.value)) } }
          .exceptionOrNull()
          ?.message

  fun deleteRate(id: Long) =
      viewModelScope.launch { withContext(NonCancellable) { repo.deleteRate(id) } }

  /** Captured here, not read in the coroutine: `bind` is a `LaunchedEffect` here too. */
  fun setArchived(v: Boolean, done: () -> Unit = {}) {
    val id = gid.value
    viewModelScope.launch {
      repo.group(id).first()?.let { repo.saveGroup(it.copy(archived = v)) }
      done()
    }
  }

  fun deleteGroup(done: () -> Unit = {}) =
      viewModelScope.launch {
        val id = gid.value
        withContext(NonCancellable) { repo.deleteGroup(id) }
        prefs.forgetGroup(id)
        ShortcutHelper.forgetGroupShortcut(app, id)
        done()
      }

  /**
   * Returns the failure message, or null on success, like the other writes here.
   *
   * Routine, not edge: an unsimplified plan row is built per expense, so it can
   * name a member who is a net creditor overall, and `recordSettlement` refuses
   * anyone not currently in the red.
   */
  suspend fun settle(from: Long, to: Long, amount: Long, note: String?): String? =
      runCatching {
        withContext(NonCancellable) {
          repo.recordSettlement(
              Settlement(
                  groupId = gid.value,
                  fromId = from,
                  toId = to,
                  amountMinor = amount,
                  dateEpoch = System.currentTimeMillis(),
                  note = note?.ifBlank { null }))
        }
      }
          .exceptionOrNull()
          ?.message

  suspend fun bundle(eid: Long) = repo.expenseBundle(eid)

  /**
   * The rows [settleAllAsync] will write: the *simplified* plan, whatever the
   * screen lists. A settlement has no currency column, so a plan row denominated
   * in an expense's own currency can be read but never written in bulk.
   */
  val settleAllCount: StateFlow<Int> =
      combine(gid, expenses, settles, rates) { id, _, _, _ -> id }
          .flatMapLatest { id -> flow { emit(repo.plan(id, true).size) } }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  /**
   * Returns the failure message, or null on success, like [settle].
   *
   * Recorded only when every row has a rate: an unpriced plan carries `pickRate`'s
   * parity figure, and settling it would record amounts the group never agreed to.
   */
  suspend fun settleAllAsync(): String? =
      runCatching { withContext(NonCancellable) { repo.settleAll(gid.value) } }
          .exceptionOrNull()
          ?.message

  suspend fun shareSummary(): String = repo.shareSummary(gid.value)

  suspend fun deleteExpense(id: Long) {
    val snap = withContext(NonCancellable) { repo.deleteExpenseWithSnapshot(id) }
    liveOffers.put(snap)
  }

  /**
   * Every delete that can still be taken back, oldest first.
   *
   * A list, not one or two slots: a bar for A on screen with B deleted into a
   * second slot would leave A's Undo restoring nothing, and a third overwrites the
   * second. State rather than a sent message, because only state a rotation's
   * composition can re-read. Room dispatches invalidation on one executor thread,
   * so [put]'s compare-and-set needs no lock; [undoLock] guards the restore.
   */
  private val liveOffers = MutableStateFlow<List<UndoOffer>>(emptyList())

  private val undoLock = Mutex()

  /** Adds an offer, dropping the oldest past [MAX_OFFERED_UNDOS]. */
  private fun MutableStateFlow<List<UndoOffer>>.put(snap: ExpenseSnapshot) {
    while (true) {
      val cur = value
      val next = (cur + UndoOffer(snap, "Expense deleted")).takeLast(MAX_OFFERED_UNDOS)
      if (compareAndSet(cur, next)) return
    }
  }

  /**
   * The delete the bar on screen offers: the newest still live. Older offers stay
   * cashable by holding the value the bar showed, which is [undoLastDelete]'s arg.
   */
  val undoOffer: StateFlow<UndoOffer?> =
      liveOffers
          .map { it.lastOrNull() }
          .distinctUntilChanged()
          .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  /**
   * The named offer was shown and not taken, so that deletion stands. Only that
   * one: declining the bar says nothing about an earlier offer the user never
   * acted on.
   */
  fun declineUndo(offer: UndoOffer? = null) {
    while (true) {
      val cur = liveOffers.value
      val target = offer ?: cur.lastOrNull() ?: return
      if (liveOffers.compareAndSet(cur, cur.filter { it !== target })) return
    }
  }

  /**
   * Restores the expense [offer] stands for, if it is not already gone, and says
   * whether it did.
   *
   * The offer leaves the list before the restore runs, not after: `restoreSnapshot`
   * inserts as new rather than refusing a row already back, so a second restore
   * would silently duplicate the expense.
   */
  suspend fun undoLastDelete(offer: UndoOffer?): Boolean {
    if (!undoLock.tryLock()) return false
    try {
      // The offer the bar showed, not the newest: a bar still on screen restores
      // the expense it announced even though later deletes have landed. A null
      // offer is a screen holding no bar, the same as one that was declined.
      val snap = offer?.snapshot ?: return false
      // The compare-and-set refuses an offer no longer in the list, so a second
      // tap finds nothing left to cash with no window where two callers both do.
      while (true) {
        val cur = liveOffers.value
        if (cur.none { it === offer }) return false
        if (liveOffers.compareAndSet(cur, cur.filter { it !== offer })) break
      }
      withContext(NonCancellable) { repo.restoreSnapshot(snap) }
      return true
    } finally {
      undoLock.unlock()
    }
  }

  fun addMember(name: String, avatarPath: String? = null) =
      viewModelScope.launch {
        if (name.isNotBlank())
            repo.saveMember(
                Member(
                    groupId = gid.value,
                    name = name.trim(),
                    createdAt = System.currentTimeMillis(),
                    avatarPath = avatarPath))
      }

  fun setMemberAvatar(id: Long, path: String?) =
      viewModelScope.launch { repo.member(id)?.let { repo.saveMember(it.copy(avatarPath = path)) } }

  val customs: StateFlow<List<CustomCategory>> =
      repo.customCats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  val subgroups = watching(emptyList<Subgroup>()) { repo.subgroups(it) }

  fun saveSubgroup(name: String, ids: List<Long>) =
      viewModelScope.launch { repo.saveSubgroup(gid.value, name, ids) }

  fun deleteSubgroup(id: Long) = viewModelScope.launch { repo.deleteSubgroup(id) }

  val templates = watching(emptyList<RecurringTemplate>()) { repo.templates(it) }

  suspend fun generateDue(): Int = withContext(NonCancellable) { repo.generateDue(gid.value) }

  fun saveTemplate(t: RecurringTemplate) =
      viewModelScope.launch { repo.saveTemplate(t.copy(groupId = gid.value)) }

  fun toggleTemplate(t: RecurringTemplate) =
      viewModelScope.launch { repo.saveTemplate(t.copy(active = !t.active)) }

  fun deleteTemplate(id: Long) = viewModelScope.launch { repo.deleteTemplate(id) }

  fun setNudge(id: Long, v: Boolean) = viewModelScope.launch { repo.setNudge(id, v) }

  private companion object {
    /**
     * How many deletes can hold a live undo bar at once. A bound rather than an
     * unbounded set: each entry carries a whole ExpenseSnapshot with its shares,
     * items and comments, retained as long as the screen is open.
     */
    const val MAX_OFFERED_UNDOS = 8
  }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchVm @Inject constructor(private val repo: SplitRepository) : ViewModel() {
  private val q = MutableStateFlow("")
  val query: StateFlow<String> = q.asStateFlow()

  fun query(s: String) {
    q.value = s
  }

  val groups: StateFlow<List<Group>> =
      repo.allGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  val hits: StateFlow<SearchHits> =
      q.debounce(250)
          .flatMapLatest { repo.search(it) }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5000),
              SearchHits(emptyList(), emptyList(), emptyList()))
}

@HiltViewModel
class StatsVm @Inject constructor(repo: SplitRepository) : ViewModel() {
  val groups: StateFlow<List<Group>> =
      repo.allGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  val expenses: StateFlow<List<Expense>> =
      repo.allExpenses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@HiltViewModel
class GroupEditVm @Inject constructor(
    private val repo: SplitRepository,
    private val prefs: SettingsPrefsStore,
    @ApplicationContext private val app: Context
) : ViewModel() {
  fun group(id: Long) = repo.group(id)

  fun members(id: Long) = repo.members(id)

  /**
   * Saves the group, then reports which members could not be removed: a removal
   * can be refused for an outstanding balance, a comment, a template, a subgroup
   * or the group's self identity, and the names come back so the screen can say
   * which survived. The edit is not rolled back.
   */
  fun save(
      name: String,
      currency: String,
      selfId: Long,
      members: List<Member>,
      gid: Long,
      gcur: String,
      color: Int = 0,
      removed: List<Long> = emptyList(),
      done: (List<String>) -> Unit = {}
  ) =
      viewModelScope.launch {
        val cur = runCatching { repo.group(gid).first() }.getOrNull()
        // A new group has no stored currency, so the typed one is it; an existing
        // one keeps what it has and the screen's copy is only a stale read.
        val code =
            if (gid == 0L) currency.uppercase()
            else gcur.ifBlank { cur?.currencyCode.orEmpty().ifBlank { currency } }
        require(code.isNotBlank()) { "group currency is unknown" }
        val target =
            cur
                ?: Group(
                    id = gid,
                    name = name.trim(),
                    currencyCode = code.uppercase(),
                    createdAt = System.currentTimeMillis())
        // The group row is written first, so the members below have a group to
        // belong to.
        val id = repo.saveGroup(target)
        // Only rows this screen created are written. An existing member is listed,
        // removable and nothing else -- there is no rename control here -- and
        // `saveMember` is a full entity UPDATE, so writing back a stub handed over
        // by a rotation would reset avatar, colour and settle reminder. A row typed
        // here is not in the database yet and holds a negative id until saved.
        val inserted =
            members.associate { m ->
              m to if (m.id <= 0L) repo.saveMember(m.copy(id = 0L, groupId = id)) else m.id
            }
        val nameOf = repo.members(id).first().associate { it.id to it.name }
        val stuck =
            removed.mapNotNull { m ->
              runCatching { repo.deleteMember(m) }.exceptionOrNull()?.let { nameOf[m] ?: "$m" }
            }
        // The name, currency, colour and self identity ride the row already in
        // hand, so nothing written since the removals above can be overwritten.
        // The id assigned by saveGroup rides along. 0 is the screen's "Nobody", so
        // the self falls back to what the group already had; a row typed here
        // resolves through the same table as the new members, so the identity
        // lands on the ticked row rather than a name-sake.
        val self = cur?.selfMemberId
        repo.saveGroup(
            target.copy(
                id = id,
                name = name.trim(),
                currencyCode = code.uppercase(),
                color = color.coerceIn(0, 4),
                selfMemberId =
                    if (selfId == 0L) self
                    else inserted.entries.firstOrNull { it.key.id == selfId }?.value ?: self))
        done(stuck)
      }

  fun deleteGroup(id: Long, done: () -> Unit = {}) =
      viewModelScope.launch {
        withContext(NonCancellable) { repo.deleteGroup(id) }
        prefs.forgetGroup(id)
        ShortcutHelper.forgetGroupShortcut(app, id)
        done()
      }
}
