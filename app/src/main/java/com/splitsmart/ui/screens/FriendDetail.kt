package com.splitsmart.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.core.settle.Transfer
import com.splitsmart.data.ExpenseBundle
import com.splitsmart.data.FxRate
import com.splitsmart.data.parseRate
import com.splitsmart.data.rateLine
import com.splitsmart.data.unpricedNote
import com.splitsmart.ui.shell.ChildShell
import com.splitsmart.ui.vm.GroupDetailVm
import com.splitsmart.ui.vm.friendTotalsIn
import com.splitsmart.ui.vm.money
import com.splitsmart.ui.vm.settleTriple
import com.splitsmart.widget.ShortcutHelper
import kotlinx.coroutines.launch

@Composable
fun FriendDetailScreen(
    gid: Long,
    addExpense: (Long) -> Unit,
    editGroup: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    openExpense: (Long, Long) -> Unit = { _, _ -> },
    vm: GroupDetailVm = hiltViewModel()
) {
  LaunchedEffect(gid) { vm.bind(gid) }
  val g by vm.group.collectAsStateWithLifecycle()
  val fctx = LocalContext.current
  LaunchedEffect(gid, g?.name) {
    val n = g?.name
    if (n != null) ShortcutHelper.onGroupOpened(fctx, gid, n)
  }
  val members by vm.members.collectAsStateWithLifecycle()
  val expenses by vm.expenses.collectAsStateWithLifecycle()
  val nets by vm.nets.collectAsStateWithLifecycle()
  val names = remember(members) { members.associate { it.id to it.name } }
  val scope = rememberCoroutineScope()
  var settleOf by rememberSaveable { mutableStateOf<Triple<Long, Long, Long>?>(null) }
  var settleErr by remember { mutableStateOf<String?>(null) }
  var settling by remember { mutableStateOf(false) }
  var share by remember { mutableStateOf(false) }
  var rateOpen by rememberSaveable { mutableStateOf(false) }
  var menu by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  var detailOf by remember { mutableStateOf<ExpenseBundle?>(null) }
  val cur = g?.currencyCode ?: ""
  val self = g?.selfMemberId
  val friend = members.firstOrNull { it.id != self } ?: members.firstOrNull()
  val net = self?.let { nets[it] } ?: 0L
  val rates by vm.rates.collectAsStateWithLifecycle()
  val localRates by vm.localRates.collectAsStateWithLifecycle()
  val sharedRates by vm.sharedRates.collectAsStateWithLifecycle()
  val totals = remember(expenses, self, cur, rates) { friendTotalsIn(expenses, self, cur, rates) }
  val unpriced = totals.unpriced
  ChildShell(
      title = friend?.name ?: g?.name ?: "",
      onBack = onBack,
      actions = {
        IconButton(onClick = { addExpense(gid) }) { Icon(Icons.Default.Add, "Add expense") }
        IconButton(onClick = { g?.let { editGroup(it.id) } }) {
          Icon(Icons.Default.Edit, "Edit friend")
        }
        Box {
          IconButton(onClick = { menu = true }) {
            Icon(Icons.Default.MoreVert, "More options")
          }
          DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                { Text("Edit friend") },
                {
                  menu = false
                  g?.let { editGroup(it.id) }
                },
                leadingIcon = { Icon(Icons.Default.Edit, null) })
            DropdownMenuItem(
                { Text(if (g?.archived == true) "Unarchive friend" else "Archive friend") },
                {
                  menu = false
                  vm.setArchived(g?.archived != true) { onBack() }
                })
            DropdownMenuItem(
                { Text("Delete friend") },
                {
                  menu = false
                  confirmDelete = true
                },
                leadingIcon = { Icon(Icons.Default.Delete, null) })
          }
        }
      },
  ) { p ->
    LazyColumn(
        Modifier.fillMaxSize().padding(p).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
          item {
            ElevatedCard(Modifier.fillMaxWidth()) {
              Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                      Text(
                          (friend?.name ?: "?").take(1).uppercase(),
                          style = MaterialTheme.typography.titleLarge,
                          color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(friend?.name ?: "", style = MaterialTheme.typography.titleLarge)
                  // Every figure below is a sum of converted expenses, and `pickRate`
                  // answers 1.0 for a pair nobody entered a rate for.
                  Text(
                      when {
                        self == null || friend == null -> "${totals.count} shared expenses"
                        unpriced.isNotEmpty() -> "${friend.name}: ${unpricedNote(unpriced)}"
                        net > 0 -> "${friend.name} owes you ${money(net, cur)}"
                        net < 0 -> "You owe ${friend.name} ${money(-net, cur)}"
                        else -> "Settled up"
                      },
                      style = MaterialTheme.typography.bodyMedium,
                      color =
                          when {
                            unpriced.isNotEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                            net > 0 -> MaterialTheme.colorScheme.tertiary
                            net < 0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                          },
                  )
                  Text(
                      if (unpriced.isEmpty())
                          "You paid ${money(totals.paidBySelf, cur)} · ${friend?.name ?: "They"} paid ${money(totals.paidByFriend, cur)}"
                      else "Add the rate to see what each of you paid.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }
          item {
            val target =
                if (self != null && friend != null) settleTriple(net, self, friend.id) else null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              // Off while a rate is missing: the amount this prefills is a parity
              // figure, and recording it writes a number nobody agreed to.
              val settleable = target != null && unpriced.isEmpty()
              Button(
                  onClick = { settleOf = target },
                  enabled = settleable,
                  modifier = Modifier.weight(1f)) {
                    Text("Settle up")
                  }
              OutlinedButton(
                  onClick = { share = true },
                  enabled = settleable,
                  modifier = Modifier.weight(1f)) {
                    Text("Share")
                  }
            }
          }
          item {
            ElevatedCard(Modifier.fillMaxWidth()) {
              Column(
                  Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                      "Currency rates (manual)",
                      modifier = Modifier.weight(1f),
                      style = MaterialTheme.typography.titleSmall)
                  TextButton(onClick = { rateOpen = true }) { Text("+ Add") }
                }
                Text(
                    "New rates are shared with all groups and friends. Existing friend-only rates take priority.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (localRates.isEmpty() && sharedRates.isEmpty())
                    Text(
                        "Add a rate here or in Settings to convert other currencies into $cur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                sharedRates.forEach { r ->
                  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("App-wide · ${rateLine(r)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { vm.deleteSharedRate(r.fromCode, r.toCode) }) {
                      Icon(Icons.Default.Delete, "Delete shared rate")
                    }
                  }
                }
                localRates.forEach { r ->
                  Row(
                      modifier = Modifier.fillMaxWidth(),
                      verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rateLine(r),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { vm.deleteRate(r.id) }) {
                      Icon(Icons.Default.Delete, "Delete rate")
                    }
                  }
                }
              }
            }
          }
          if (friend != null)
              item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                  Text(
                      "Settle-up reminders",
                      modifier = Modifier.weight(1f),
                      style = MaterialTheme.typography.bodyMedium)
                  Switch(friend.settleNudge, { vm.setNudge(friend.id, it) })
                }
              }
          if (expenses.isEmpty())
              item {
                Text(
                    "No shared expenses yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
          items(expenses, key = { it.id }) { e ->
            ListItem(
                headlineContent = { Text(e.note?.takeIf { it.isNotBlank() } ?: e.category.name) },
                supportingContent = {
                  Text("${names[e.payerId] ?: "?"} paid ${money(e.amountMinor, e.currencyCode)}")
                },
                modifier = Modifier.clickable { scope.launch { detailOf = vm.bundle(e.id) } },
            )
            HorizontalDivider()
          }
        }
  }
  if (confirmDelete) {
    AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete friend?") },
        text = { Text("Removes this friend and all shared expenses. This cannot be undone.") },
        confirmButton = {
          TextButton(onClick = {
            confirmDelete = false
            vm.deleteGroup { onBack() }
          }) { Text("Delete") }
        },
        dismissButton = {
          TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
        })
  }
  if (rateOpen) {
    var from by remember { mutableStateOf("") }
    var to by remember(cur) { mutableStateOf(cur) }
    var rate by remember { mutableStateOf("") }
    var rateErr by remember { mutableStateOf<String?>(null) }
    var savingRate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!savingRate) rateOpen = false },
        title = { Text("Add app-wide rate") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "1 unit of currency A = ? units of currency B.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                  from,
                  { from = it.uppercase().take(3); rateErr = null },
                  label = { Text("From") },
                  singleLine = true,
                  modifier = Modifier.weight(1f))
              OutlinedTextField(
                  to,
                  { to = it.uppercase().take(3); rateErr = null },
                  label = { Text("To") },
                  singleLine = true,
                  modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                rate,
                { rate = it; rateErr = null },
                label = { Text("Rate") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth())
            rateErr?.let {
              Text(it, color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodySmall)
            }
          }
        },
        confirmButton = {
          TextButton(
              enabled = !savingRate,
              onClick = {
                val parsed = parseRate(rate)
                rateErr =
                    when {
                      from.length != 3 || to.length != 3 -> "Enter two 3-letter codes."
                      from == to -> "Choose two different currencies."
                      parsed == null -> "Enter a positive rate."
                      else -> null
                    }
                if (rateErr == null && parsed != null) {
                  savingRate = true
                  scope.launch {
                    val problem =
                        vm.saveRate(
                            FxRate(
                                groupId = gid,
                                fromCode = from,
                                toCode = to,
                                rate = parsed,
                                timeEpoch = System.currentTimeMillis()))
                    savingRate = false
                    if (problem == null) rateOpen = false else rateErr = problem
                  }
                }
              }) { Text("Save") }
        },
        dismissButton = {
          TextButton(enabled = !savingRate, onClick = { rateOpen = false }) { Text("Cancel") }
        })
  }
  settleOf?.let { (f, t, a) ->
    SettleDialog(
        names[f] ?: "?",
        names[t] ?: "?",
        a,
        cur,
        settleErr,
        { settleOf = null; settleErr = null },
        { amt, note ->
          if (settling) return@SettleDialog
          settling = true
          scope.launch {
            val err = vm.settle(f, t, amt, note)
            settling = false
            if (err == null) settleOf = null else settleErr = err
          }
        },
        settling)
  }
  if (share && self != null && friend != null) {
    settleTriple(net, self, friend.id)?.let { (f, t, a) ->
      SettleShareSheet(friend.name, listOf(Transfer(f, t, a, cur)), names) { share = false }
    } ?: run { share = false }
  }
  detailOf?.let { b ->
    val thread by
        remember(b.expense.id) { vm.comments(b.expense.id) }
            .collectAsStateWithLifecycle(emptyList())
    val customs by vm.customs.collectAsStateWithLifecycle()
    ExpenseDetailDialog(
        b,
        names,
        cur,
        customs.associateBy { it.id },
        { detailOf = null },
        thread.size,
        {
          detailOf = null
          openExpense(gid, b.expense.id)
        })
  }
}
