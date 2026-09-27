package com.splitsmart.ui.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitsmart.core.splits.*
import com.splitsmart.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SplitRow(val id: Long, val name: String, included: Boolean = true, text: String = "") {
  var included by mutableStateOf(included)
  var text by mutableStateOf(text)

  fun copy(text: String = this.text, included: Boolean = this.included) =
      SplitRow(id, name, included, text)
}

@HiltViewModel
class ExpenseVm
@Inject
constructor(private val repo: SplitRepository, private val defaults: GroupDefaultsStore) :
    ViewModel() {
  /**
   * The group's saved defaults, or null while the load is in flight.
   *
   * Null means "not known yet" and nothing else, so [loadDefaults] publishes a
   * resolved value rather than leaving the previous group's in place.
   */
  private val _prefill = MutableStateFlow<GroupDefaults?>(null)
  val prefill: StateFlow<GroupDefaults?> = _prefill

  /**
   * Whether [loadDefaults] has resolved, which a null [prefill] cannot say.
   *
   * The editor's defaults and its member list come from two different stores -- this
   * one reads DataStore, the other Room -- and neither is ordered before the other.
   * So members arriving first read as "there are no defaults" and the editor
   * settles on the first member as payer, and the real prefill is discarded. Set
   * when the read resolves, whether or not it found anything.
   */
  private val _prefillLoaded = MutableStateFlow(false)
  val prefillLoaded: StateFlow<Boolean> = _prefillLoaded

  /** The load in flight, cancelled by the next one. Nothing orders two of them. */
  private var loadJob: Job? = null

  /**
   * Cleared before the load, not after it.
   *
   * This value outlives the group it was loaded for, and the editor gates its own
   * application of a default on `applied`, so a prefill for the previous group
   * stays readable while the next one is in flight.
   */
  fun loadDefaults(gid: Long) {
    loadJob?.cancel()
    loadJob =
        viewModelScope.launch {
          _prefill.value = null
          _prefillLoaded.value = false
          _prefill.value = defaults.load(gid)
          _prefillLoaded.value = true
        }
  }

  fun preview(total: Long, rows: List<SplitRow>, type: SplitType): Map<Long, Long>? {
    val p = parseInput(total, rows, type)
    if (!p.valid) return null
    return runCatching { SplitRepository.calcShares(total, p.parts, p.input) }.getOrNull()
  }

  suspend fun bundle(eid: Long) = repo.expenseBundle(eid)

  suspend fun subgroupMembers(gid: Long, sid: Long) =
      runCatching { repo.subgroupMembers(sid) }.getOrNull()

  suspend fun duplicateLast(gid: Long): Expense? {
    val last = repo.recent(gid, 1).first().firstOrNull() ?: return null
    return last.copy(
        id = 0, dateEpoch = System.currentTimeMillis(), createdAt = System.currentTimeMillis())
  }

  fun save(
      gid: Long,
      payer: Long,
      total: Long,
      cat: Category,
      note: String?,
      rows: List<SplitRow>,
      type: SplitType,
      cur: String,
      eid: Long = 0,
      items: List<ExpenseItem> = emptyList(),
      pays: Map<Long, Long>? = null,
      receipt: String? = null,
      customId: Long? = null,
      tipMinor: Long = 0L,
      taxMinor: Long = 0L,
      dateEpoch: Long = 0L,
      onDone: () -> Unit = {}
  ) =
      viewModelScope.launch {
        val p = parseInput(total, rows, type)
        if (!p.valid) return@launch
        repo.saveExpense(
            Expense(
                id = eid,
                groupId = gid,
                payerId = payer,
                amountMinor = total,
                currencyCode = cur,
                category = cat,
                note = note?.ifBlank { null },
                // The date is money, not metadata: `pickRate` picks a rate effective
                // at the expense's date, so an edit that re-dated a March expense
                // converted it at whatever rate June held. A note edit moved money.
                dateEpoch = if (dateEpoch > 0L) dateEpoch else System.currentTimeMillis(),
                receiptUri = receipt,
                createdAt = System.currentTimeMillis(),
                customCatId = customId,
                tipMinor = tipMinor,
                taxMinor = taxMinor),
            p.parts,
            p.input,
            pays,
            items,
        )
        defaults.save(gid, GroupDefaults(payer, p.parts, cat, type))
        onDone()
      }
}
