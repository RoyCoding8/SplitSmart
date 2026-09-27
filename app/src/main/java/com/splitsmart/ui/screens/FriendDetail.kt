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
  var detailOf by remember { mutableStateOf<ExpenseBundle?>(null) }
  val cur = g?.currencyCode ?: ""
  val self = g?.selfMemberId
  val friend = members.firstOrNull { it.id != self } ?: members.firstOrNull()
  val net = self?.let { nets[it] } ?: 0L
  val rates by vm.rates.collectAsStateWithLifecycle()
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
