package com.splitsmart.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.core.splits.TipMode
import com.splitsmart.core.splits.allPays
import com.splitsmart.core.splits.isSplitPayment
import com.splitsmart.core.splits.itemized
import com.splitsmart.core.splits.sumsTo
import com.splitsmart.data.Category
import com.splitsmart.data.ExpenseItem
import com.splitsmart.data.SplitType
import com.splitsmart.data.receiptFile
import com.splitsmart.ui.shell.ChildShell
import com.splitsmart.ui.vm.ExpenseVm
import com.splitsmart.ui.vm.GroupDetailVm
import com.splitsmart.ui.vm.SplitRow
import com.splitsmart.ui.vm.majorToMinor
import com.splitsmart.ui.vm.minorToDecimal
import com.splitsmart.ui.vm.money
import com.splitsmart.ui.vm.splitRowTexts
import java.io.File
import kotlinx.coroutines.launch

/**
 * A snapshot list of split rows as a flat list of their fields. A `SplitRow` holds its
 * own `MutableState`, so without flattening the list would be saved and the per-row
 * values would not.
 */
private val splitRowListSaver =
    listSaver<SnapshotStateList<SplitRow>, Any>(
        save = { rows -> rows.flatMap { listOf(it.id, it.name, it.included, it.text) } },
        restore = { flat ->
          mutableStateListOf<SplitRow>().apply {
            for (i in flat.indices step 4) {
              add(
                  SplitRow(
                      flat[i] as Long, flat[i + 1] as String, flat[i + 2] as Boolean, flat[i + 3] as String))
            }
          }
        })

/**
 * A saved enum, held as its name: a reordered or removed entry would reinterpret every
 * editor an earlier one saved, and a name that no longer resolves falls back.
 */
private class EnumField<T : Enum<T>>(
    private val state: MutableState<String>,
    private val values: Array<T>,
    private val fallback: T
) : ReadWriteProperty<Any?, T> {
  override fun getValue(thisRef: Any?, property: KProperty<*>): T =
      values.firstOrNull { it.name == state.value } ?: fallback

  override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
    state.value = value.name
  }
}

private fun enumField(state: MutableState<String>, fallback: Category) =
    EnumField(state, Category.entries.toTypedArray(), fallback)
private fun enumField(state: MutableState<String>, fallback: SplitType) =
    EnumField(state, SplitType.entries.toTypedArray(), fallback)
private fun enumField(state: MutableState<String>, fallback: TipMode) =
    EnumField(state, TipMode.entries.toTypedArray(), fallback)

/** A touched-field set over a saved list of names. */
private val touchedSetSaver =
    listSaver<MutableState<Set<String>>, String>(
        save = { it.value.toList() },
        restore = { l -> mutableStateOf(l.toSet()) })

/** A nullable `Long` over a value and a presence flag: "no value" is not "zero". */
private class NullableLongField(
    private val value: MutableLongState,
    private val present: MutableState<Boolean>
) : ReadWriteProperty<Any?, Long?> {
  override fun getValue(thisRef: Any?, property: KProperty<*>): Long? =
      if (present.value) value.longValue else null

  override fun setValue(thisRef: Any?, property: KProperty<*>, v: Long?) {
    value.longValue = v ?: 0L
    present.value = v != null
  }
}

/** A nullable `String` over a value and a presence flag, for the same reason as [NullableLongField]. */
private class NullableStringField(
    private val value: MutableState<String>,
    private val present: MutableState<Boolean>
) : ReadWriteProperty<Any?, String?> {
  override fun getValue(thisRef: Any?, property: KProperty<*>): String? =
      if (present.value) value.value else null

  override fun setValue(thisRef: Any?, property: KProperty<*>, v: String?) {
    value.value = v ?: ""
    present.value = v != null
  }
}

/**
 * A member-to-amount map over two saved arrays and a presence flag. It puts a reopened
 * bill's co-payers back on the ledger, so it has to survive a rotation; keys and values
 * are kept as one array each and re-zipped, so the pairing is an index.
 */
private class LongMapField(
    private val ids: MutableState<LongArray>,
    private val amounts: MutableState<LongArray>,
    private val present: MutableState<Boolean>
) : ReadWriteProperty<Any?, Map<Long, Long>?> {
  override fun getValue(thisRef: Any?, property: KProperty<*>): Map<Long, Long>? {
    if (!present.value) return null
    val keys = ids.value
    val values = amounts.value
    return keys.indices.associate { keys[it] to values[it] }
  }

  override fun setValue(thisRef: Any?, property: KProperty<*>, v: Map<Long, Long>?) {
    ids.value = v?.keys?.toLongArray() ?: longArrayOf()
    amounts.value = v?.values?.toLongArray() ?: longArrayOf()
    present.value = v != null
  }
}

@Composable
fun ExpenseEditorScreen(
    gid: Long,
    expenseId: Long? = null,
    evm: ExpenseVm = hiltViewModel(),
    gvm: GroupDetailVm = hiltViewModel(),
    done: () -> Unit = {}
) {
  LaunchedEffect(gid) {
    gvm.bind(gid)
    evm.loadDefaults(gid)
  }
  val g by gvm.group.collectAsStateWithLifecycle()
  val members by gvm.members.collectAsStateWithLifecycle()
  val prefill by evm.prefill.collectAsStateWithLifecycle()
  val prefillLoaded by evm.prefillLoaded.collectAsStateWithLifecycle()
  val subgroups by gvm.subgroups.collectAsStateWithLifecycle()
  val customs by gvm.customs.collectAsStateWithLifecycle()
  val customMap = remember(customs) { customs.associateBy { it.id } }
  val scope = rememberCoroutineScope()
  // Saveable, not merely remembered: `remember` does not cross a rotation.
  var amount by rememberSaveable { mutableStateOf("") }
  var cur by rememberSaveable { mutableStateOf("") }
  var note by rememberSaveable { mutableStateOf("") }
  var tip by rememberSaveable { mutableStateOf("") }
  var tax by rememberSaveable { mutableStateOf("") }
  var payer by rememberSaveable { mutableLongStateOf(-1L) }
  var editId by rememberSaveable { mutableLongStateOf(0L) }
  // The expense's own date, held so a save can put it back -- see ExpenseVm.save
  // for why the date is money. Zero means "new", where now is the answer.
  var dateEpoch by rememberSaveable { mutableLongStateOf(0L) }
  var multi by rememberSaveable { mutableStateOf(false) }
  var applied by rememberSaveable { mutableStateOf(false) }
  var itemized by rememberSaveable { mutableStateOf(false) }
  // The fields the user has set themselves, so a late-arriving default does not
  // overwrite their input. Touch alone counts: a member added after the first pass
  // re-enters this effect, and a second pass must not retype over anything.
  val touched = rememberSaveable(saver = touchedSetSaver) { mutableStateOf(emptySet<String>()) }
  fun touched(field: String) = field in touched.value
  fun touch(field: String) { touched.value = touched.value + field }
  val catName = rememberSaveable { mutableStateOf(Category.OTHER.name) }
  var cat: Category by enumField(catName, Category.OTHER)
  val typeName = rememberSaveable { mutableStateOf(SplitType.EQUAL.name) }
  var type: SplitType by enumField(typeName, SplitType.EQUAL)
  val tipModeName = rememberSaveable { mutableStateOf(TipMode.PROPORTIONAL.name) }
  var tipMode: TipMode by enumField(tipModeName, TipMode.PROPORTIONAL)
  val ctx = LocalContext.current
  val customIdValue = rememberSaveable { mutableLongStateOf(0L) }
  val customIdSet = rememberSaveable { mutableStateOf(false) }
  var customId: Long? by NullableLongField(customIdValue, customIdSet)
  val receiptValue = rememberSaveable { mutableStateOf("") }
  val receiptSet = rememberSaveable { mutableStateOf(false) }
  var receiptPath: String? by NullableStringField(receiptValue, receiptSet)
  // The payments the expense was reopened with, so saving can put them back.
  // `allPays` is the only way to author a payment map in this editor and it always
  // divides evenly -- see isSplitPayment for why it cannot be recomputed on every save.
  val storedPaysIds = rememberSaveable { mutableStateOf(longArrayOf()) }
  val storedPaysAmts = rememberSaveable { mutableStateOf(longArrayOf()) }
  val storedPaysSet = rememberSaveable { mutableStateOf(false) }
  var storedPays: Map<Long, Long>? by LongMapField(storedPaysIds, storedPaysAmts, storedPaysSet)
  var catOpen by remember { mutableStateOf(false) }
  // A camera intent in flight, and the scratch file behind it. Both are saveable: a
  // rotation recreates the composition, leaving `ok` true and `pendingUri` null when
  // the result arrives, and the photo is silently discarded.
  var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingFilePath by rememberSaveable { mutableStateOf<String?>(null) }
  var receiptErr by remember { mutableStateOf<String?>(null) }
  val camera =
      rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingUri
        if (ok && uri != null) receiptPath = uri
        else {
          if (!ok) receiptErr = "Photo was not taken."
          pendingFilePath?.let { runCatching { File(it).delete() } }
          pendingFilePath = null
          pendingUri = null
        }
      }
  val rows =
      rememberSaveable(saver = splitRowListSaver) {
        mutableStateListOf<SplitRow>()
      }
  LaunchedEffect(members) {
    val keep = rows.associate { it.id to (it.included to it.text) }
    rows.clear()
    rows.addAll(
        members.map { m ->
          val (inc, txt) = keep[m.id] ?: (true to "")
          SplitRow(m.id, m.name, inc, txt)
        })
  }
  LaunchedEffect(prefill, prefillLoaded, members, expenseId) {
    if (!applied && members.isNotEmpty()) {
      if (expenseId != null && expenseId != 0L) {
        evm.bundle(expenseId)?.let { b ->
          if (!touched("payer")) payer = b.expense.payerId
          if (!touched("amount")) amount = minorToDecimal(b.expense.amountMinor)
          if (!touched("note")) note = b.expense.note ?: ""
          if (!touched("cat")) cat = b.expense.category
          // The type is stored, not guessed from the presence of item rows: a percent or
          // shares split with no itemized lines would come back as EQUAL. The split rows
          // derive from the type, so overwriting one rewrites every amount entered against it.
          if (!touched("type")) type = if (b.items.isNotEmpty()) SplitType.EXACT else b.expense.splitType
          val total = b.expense.amountMinor
          // The rows move together, so they are guarded as one field: half of a set is
          // not a state a bill can be saved from.
          if (!touched("rows")) {
            rows.forEach { it.included = it.id in b.shares }
            // Converted across the whole set at once: a percent split's basis points
            // and a shares split's weights are only defined over every row.
            val texts = splitRowTexts(b.shares, total, type)
            rows.forEach { it.text = texts[it.id] ?: "" }
          }
          receiptPath = b.expense.receiptUri
          dateEpoch = b.expense.dateEpoch
          editId = b.expense.id
          cur = b.expense.currencyCode
          customId = b.expense.customCatId
          // Saving rewrites the payment rows from whatever this editor hands it, so
          // the toggle has to come back with the expense or the whole cost lands on
          // the named payer.
          multi = isSplitPayment(b.payments, b.expense.payerId, total)
          storedPays = b.payments
          val storedItems = b.items.associate { it.label to it.amountMinor }
          itemized = b.items.isNotEmpty()
          if (itemized) {
            // rows[].text holds the per-participant item subtotal here, which is
            // a different number from their share, so it is restored by label.
            rows.forEach {
              it.text = storedItems["${it.name} items"]?.let(::minorToDecimal) ?: ""
            }
            tip = minorToDecimal(b.expense.tipMinor)
            tax = minorToDecimal(b.expense.taxMinor)
          }
          if (customId == null) cat = b.expense.category
        }
      } else {
        prefill?.let { d ->
          if (payer < 0 || !touched("payer")) {
            payer =
                d.payerId?.takeIf { id -> members.any { it.id == id } } ?: members.first().id
          }
          if (d.memberIds.isNotEmpty() && d.memberIds.all { id -> members.any { it.id == id } })
              rows.forEach { it.included = it.id in d.memberIds }
        }
        // Members and defaults arrive from different stores, in no order between
        // them, so settling the payer before the defaults land picks whoever
        // happens to be first. `prefillLoaded` is what says they have been read.
        if (prefillLoaded && payer < 0) payer = members.first().id
      }
      applied = true
    }
  }
  val total = (majorToMinor(amount) ?: 0L).coerceAtLeast(0L)
  val preview =
      remember(total, rows.map { Triple(it.id, it.included, it.text) }, type) {
        evm.preview(total, rows, type)
      }
  val tipMinor = (majorToMinor(tip) ?: 0L).coerceAtLeast(0L)
  val taxMinor = (majorToMinor(tax) ?: 0L).coerceAtLeast(0L)
  val itemSubs =
      remember(itemized, rows.map { Triple(it.id, it.included, it.text) }) {
        if (!itemized) null
        else
            runCatching {
                  rows
                      .filter { it.included }
                      .associate {
                        it.id to
                            (majorToMinor(it.text) ?: throw NumberFormatException()).also { v ->
                              require(v >= 0)
                            }
                      }
                }
                .getOrNull()
      }
  val itemResult =
      remember(itemSubs, tipMinor, taxMinor, tipMode) {
        itemSubs?.let { subs ->
          if (subs.isEmpty()) null
          else
              runCatching {
                    val sub = com.splitsmart.core.splits.checkedSum(subs.values)
                    val extra = Math.addExact(tipMinor, taxMinor)
                    itemized(subs, sub, tipMinor, taxMinor, tipMode) to Math.addExact(sub, extra)
                  }
                  .getOrNull()
        }
      }
  LaunchedEffect(g) { if (cur.isBlank()) cur = g?.currencyCode ?: "" }
  // In itemized mode the total is the sum of the items, tip and tax, not the amount field.
  val canSave =
      g != null &&
          payer >= 0 &&
          cur.length == 3 &&
          (if (itemized) (itemResult?.second ?: 0L) > 0 else total > 0 && preview != null)
  // The toggle decides whether there are co-payers at all, and the stored map only rides
  // along while it is on. The map is re-checked against this bill's total rather than
  // trusted, because in itemized mode the total is the sum of the items and the tip.
  fun paymentsFor(grand: Long): Map<Long, Long>? {
    if (!multi) return null
    val ids = rows.filter { it.included }.map { it.id }
    storedPays?.takeIf { sumsTo(it.values, grand) }?.let { return it }
    return runCatching { allPays(grand, ids) }.getOrNull()
  }
  // A write in flight. `canSave` does not change until the write lands, and `evm.save`
  // only pops the back stack from its completion, so without this two taps meant two writes.
  var saving by rememberSaveable { mutableStateOf(false) }
  val doSave = run@{
    if (saving) return@run
    saving = true
    if (cur.length == 3) {
      if (itemized) {
        itemResult?.let { (shares, grand) ->
          val exactRows = rows.map { it.copy(text = minorToDecimal(shares[it.id] ?: 0L)) }
          val items =
              rows
                  .filter { it.included }
                  .map {
                    ExpenseItem(
                        expenseId = 0,
                        label = "${it.name} items",
                        amountMinor = itemSubs?.get(it.id) ?: 0L)
                  }
          evm.save(
              gid,
              payer,
              grand,
              cat,
              note,
              exactRows,
              SplitType.EXACT,
              cur,
              eid = editId,
              items = items,
              pays = paymentsFor(grand),
              receipt = receiptPath,
              customId = customId,
              dateEpoch = dateEpoch,
              tipMinor = tipMinor,
              taxMinor = taxMinor,
              onDone = done)
        }
      } else {
        evm.save(
            gid,
            payer,
            total,
            cat,
            note,
            rows,
            type,
            cur,
            eid = editId,
            pays = paymentsFor(total),
            receipt = receiptPath,
            customId = customId,
            dateEpoch = dateEpoch,
            onDone = done)
      }
    } else saving = false
  }
  ChildShell(
      title = if (expenseId == null || expenseId == 0L) "New expense" else "Edit expense",
      onBack = done,
      actions = { TextButton(onClick = doSave, enabled = canSave && !saving) { Text("Save") } },
  ) { p ->
    Column(
        Modifier.fillMaxSize().padding(p).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (itemized) {
              // Read-only in this mode: the amount is what the items add up to.
              val grand = itemResult?.second ?: 0L
              OutlinedTextField(
                  if (grand > 0) minorToDecimal(grand) else "",
                  {},
                  label = { Text("Total ($cur)") },
                  readOnly = true,
                  singleLine = true,
                  modifier = Modifier.weight(1f))
            } else
                OutlinedTextField(
                    amount,
                    {
                      amount = it
                      touch("amount")
                    },
                    label = { Text("Amount ($cur)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f))
            OutlinedTextField(
                cur,
                { cur = it.uppercase().take(3) },
                label = { Text("Cur") },
                singleLine = true,
                modifier = Modifier.width(96.dp))
            TextButton(
                onClick = {
                  scope.launch {
                    evm.duplicateLast(gid)?.let { e ->
                      amount = minorToDecimal(e.amountMinor)
                      touch("amount")
                      customId = e.customCatId
                      cat = e.category
                      touch("cat")
                    }
                  }
                }) {
                  Text("Repeat last")
                }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
              OutlinedButton(onClick = { catOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    customId?.let { customMap[it]?.let { c -> "${c.emoji} ${c.name}" } }
                        ?: cat.name)
              }
              DropdownMenu(catOpen, { catOpen = false }) {
                Category.entries.forEach { c ->
                  DropdownMenuItem(
                      { Text(c.name) },
                      {
                        cat = c
                        touch("cat")
                        customId = null
                        catOpen = false
                      })
                }
                if (customs.isNotEmpty()) {
                  HorizontalDivider()
                  customs.forEach { c ->
                    DropdownMenuItem(
                        { Text("${c.emoji} ${c.name}") },
                        {
                          customId = c.id
                          touch("cat")
                          catOpen = false
                        })
                  }
                }
              }
            }
          }
          Text("Paid by", style = MaterialTheme.typography.labelLarge)
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.horizontalScroll(rememberScrollState())) {
                members.forEach { m ->
                  FilterChip(
                      payer == m.id,
                      {
                        payer = m.id
                        touch("payer")
                      },
                      label = { Text(m.name) })
                }
              }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Split between all", modifier = Modifier.weight(1f))
            Switch(!multi, { multi = !it })
          }
          if (multi) {
            Text(
                "Split equally between payers (tap to include)",
                style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                  members.forEach { m ->
                    FilterChip(
                        rows.firstOrNull { it.id == m.id }?.included == true,
                        { rows.firstOrNull { it.id == m.id }?.let { it.included = !it.included } },
                        label = { Text(m.name) })
                  }
                }
          }
          if (subgroups.isNotEmpty()) {
            Text("Preset", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                  subgroups.forEach { sg ->
                    AssistChip(
                        onClick = {
                          scope.launch {
                            evm.subgroupMembers(gid, sg.id)?.let { ids ->
                              touch("rows")
                              rows.forEach { it.included = it.id in ids }
                            }
                          }
                        },
                        label = { Text(sg.name) })
                  }
                }
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Itemized bill", modifier = Modifier.weight(1f))
            Switch(itemized, { itemized = it })
          }
          if (itemized) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                  tip,
                  { tip = it },
                  label = { Text("Tip") },
                  singleLine = true,
                  modifier = Modifier.weight(1f))
              OutlinedTextField(
                  tax,
                  { tax = it },
                  label = { Text("Tax") },
                  singleLine = true,
                  modifier = Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                  FilterChip(
                      tipMode == TipMode.PROPORTIONAL,
                      { tipMode = TipMode.PROPORTIONAL },
                      label = { Text("Tip ∝ subtotal") })
                  FilterChip(
                      tipMode == TipMode.EQUAL,
                      { tipMode = TipMode.EQUAL },
                      label = { Text("Tip equal") })
                }
          }
          Text("Split", style = MaterialTheme.typography.labelLarge)
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SplitType.entries.forEach { t ->
                  FilterChip(
                      type == t,
                      {
                        type = t
                        touch("type")
                        itemized = false
                      },
                      label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
              }
          LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows, key = { it.id }) { r ->
              // In itemized mode the per-row number is the participant's own item
              // subtotal; outside it, a percent or shares split enters its own basis.
              // Same field, so one branch picks the label.
              val rowLabel =
                  when {
                    itemized -> "items"
                    type == SplitType.PERCENT -> "%"
                    type != SplitType.EQUAL -> "amt"
                    else -> null
                  }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    r.included,
                    {
                      r.included = it
                      touch("rows")
                    })
                Text(r.name, modifier = Modifier.weight(1f).padding(top = 12.dp))
                if (rowLabel != null)
                    OutlinedTextField(
                        r.text,
                        {
                          r.text = it
                          touch("rows")
                        },
                        label = { Text(rowLabel) },
                        singleLine = true,
                        modifier = Modifier.width(110.dp))
                else
                    Text(
                        preview?.get(r.id)?.let { money(it, cur) } ?: "",
                        modifier = Modifier.padding(top = 12.dp))
              }
            }
          }
          // In itemized mode the check keys off the rows having entries at all, not off
          // the amount field -- there the amount is derived.
          if (itemized && itemResult == null && itemSubs != null)
              Text("Item amounts must be valid numbers.", color = MaterialTheme.colorScheme.error)
          else if (itemized && itemSubs == null && rows.any { it.included })
              Text(
                  "Enter an amount for each person who had items.",
                  color = MaterialTheme.colorScheme.error)
          else if (!itemized && preview == null && total > 0)
              Text(
                  "Shares don't add up. Check the amounts.",
                  color = MaterialTheme.colorScheme.error)
          Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                      receiptErr = null
                      val handler =
                          Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                              .resolveActivity(ctx.packageManager) != null
                      if (!handler) {
                        receiptErr = "No camera app found on this device."
                      } else
                          runCatching {
                                val f = receiptFile(ctx.filesDir, gid, editId)
                                val uri =
                                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                                pendingUri = uri.toString()
                                pendingFilePath = f.absolutePath
                                camera.launch(uri)
                              }
                              .onFailure { receiptErr = "Could not open the camera." }
                    },
                    modifier = Modifier.weight(1f)) {
                      Text(if (receiptPath == null) "Add receipt photo" else "Retake receipt")
                    }
                if (receiptPath != null)
                    TextButton(
                        onClick = {
                          pendingFilePath?.let { runCatching { File(it).delete() } }
                          pendingFilePath = null
                          pendingUri = null
                          receiptPath = null
                        }) {
                          Text("Remove")
                        }
              }
          receiptErr?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
          }
          receiptPath?.let { rp ->
            remember(rp) {
              runCatching {
                  val uri = Uri.parse(rp)
                  val decode: (BitmapFactory.Options?) -> android.graphics.Bitmap? =
                      if (uri.scheme == "content" || uri.scheme == "file")
                          { opts ->
                            ctx.contentResolver.openInputStream(uri)?.use {
                              BitmapFactory.decodeStream(it, null, opts)
                            }
                          }
                      else { opts -> BitmapFactory.decodeFile(uri.path ?: rp, opts) }
                  // Read the bounds first, then halve until the bitmap fits 1024px on its
                  // long edge, so a full-size camera shot is never decoded whole.
                  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                  decode(bounds)
                  var sample = 1
                  while (bounds.outWidth / sample > 1024 ||
                      bounds.outHeight / sample > 1024) sample *= 2
                  decode(BitmapFactory.Options().apply { inSampleSize = sample })
                }
                .getOrNull()
                ?.asImageBitmap()
            }
            ?.let { Image(it, "receipt", modifier = Modifier.fillMaxWidth().height(160.dp)) }
          }
          OutlinedTextField(
              note,
              {
                note = it
                touch("note")
              },
              label = { Text("Note (optional)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth())
        }
  }
}
