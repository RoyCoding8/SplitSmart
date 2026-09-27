package com.splitsmart.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.splitsmart.core.settle.Transfer
import com.splitsmart.data.CustomCategory
import com.splitsmart.data.EventKind
import com.splitsmart.data.ExpenseBundle
import com.splitsmart.data.FxRate
import com.splitsmart.data.avatarFile
import com.splitsmart.data.pendingAvatarFile
import com.splitsmart.data.catLabel
import com.splitsmart.data.lookupRate
import com.splitsmart.data.parseRate
import com.splitsmart.data.rateLine
import com.splitsmart.data.unconvertibleCurrencies
import com.splitsmart.data.unpricedNote
import com.splitsmart.data.totalIn
import com.splitsmart.ui.shell.ChildShell
import com.splitsmart.ui.vm.GroupDetailVm
import com.splitsmart.ui.vm.majorToMinor
import com.splitsmart.ui.vm.minorToDecimal
import com.splitsmart.ui.vm.money
import com.splitsmart.ui.vm.settlementAmountIn
import com.splitsmart.widget.ShortcutHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The pair of member ids a settle dialog is open for, over a saved list of two longs. */
private val settlePairSaver =
    listSaver<MutableState<Pair<Long, Long>>, Long>(
        save = { listOf(it.value.first, it.value.second) },
        restore = { l -> mutableStateOf(l[0] to l[1]) })

@Composable
fun GroupDetailScreen(
    gid: Long,
    addExpense: (Long) -> Unit,
    editExpense: (Long, Long) -> Unit = { _, _ -> },
    charts: (Long) -> Unit = {},
    recurring: (Long) -> Unit = {},
    editGroup: (Long) -> Unit = {},
    history: (Long) -> Unit = {},
    settings: () -> Unit = {},
    onBack: () -> Unit = {},
    openExpense: (Long, Long) -> Unit = { _, _ -> },
    vm: GroupDetailVm = hiltViewModel()
) {
  // The dialog is identified by its two ends, not by the Transfer, because a Transfer is
  // not a saveable type. Both ends are 0 in the closed state, and a member id is never 0.
  val settleOf = rememberSaveable(saver = settlePairSaver) { mutableStateOf(0L to 0L) }
  fun openSettle(t: Transfer) {
    settleOf.value = t.from to t.to
  }
  fun closeSettle() {
    settleOf.value = 0L to 0L
  }
  // A refused payment holds the dialog open and says why.
  var settleErr by remember { mutableStateOf<String?>(null) }
  var share by remember { mutableStateOf(false) }
  LaunchedEffect(gid) { vm.bind(gid) }
  val g by vm.group.collectAsStateWithLifecycle()
  val members by vm.members.collectAsStateWithLifecycle()
  val expenses by vm.expenses.collectAsStateWithLifecycle()
  val plan by vm.plan.collectAsStateWithLifecycle()
  val settleAllCount by vm.settleAllCount.collectAsStateWithLifecycle()
  val nets by vm.nets.collectAsStateWithLifecycle()
  val subgroups by vm.subgroups.collectAsStateWithLifecycle()
  val rates by vm.rates.collectAsStateWithLifecycle()
  val native by vm.netsNative.collectAsStateWithLifecycle()
  val customs by vm.customs.collectAsStateWithLifecycle()
  val customMap = remember(customs) { customs.associateBy { it.id } }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // Opening a group records it as the last one used and refreshes the pinned shortcut.
  // Keyed on the id and the name, so neither a rename nor a navigate-away can leave the
  // shortcut pointing at another group.
  LaunchedEffect(gid, g?.name) { g?.let { ShortcutHelper.onGroupOpened(context, gid, it.name) } }
  var addOpen by remember { mutableStateOf(false) }
  var removeOf by remember { mutableStateOf<Long?>(null) }
  var rateOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  // One picker serves both avatar sites. The picked image lands in a scratch file, and
  // the caller says which member, if any, it is for; `avatarFor == null` is the Add member
  // dialog, which has no member row yet.
  var avatarFor by remember { mutableStateOf<Long?>(null) }
  var newAvatar by remember { mutableStateOf<String?>(null) }
  val pickAvatar =
      rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val existing = avatarFor
        avatarFor = null
        val target = existing?.takeIf { it > 0L }
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
          runCatching {
            val file =
                context.contentResolver.openInputStream(uri)?.use { ins ->
                  val f = if (target == null) pendingAvatarFile(context.filesDir, gid)
                  else avatarFile(context.filesDir, gid, target)
                  f.outputStream().use { ins.copyTo(it) }
                  f
                }
            if (target == null) newAvatar = file?.absolutePath
            else if (file != null) vm.setMemberAvatar(target, file.absolutePath)
          }
        }
      }
  fun pickImage() {
    avatarFor = null
    pickAvatar.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
  }
  var sgName by remember { mutableStateOf("") }
  var sgSel by remember { mutableStateOf(emptySet<Long>()) }
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var menu by remember { mutableStateOf(false) }
  // The preset rows toggle the same value, once by tap and once by the checkbox itself.
  // One toggle, so the two cannot drift apart.
  fun toggleMember(id: Long) {
    sgSel = if (sgSel.contains(id)) sgSel - id else sgSel + id
  }
  // Read from the view model, which owns the one copy, rather than mirrored here.
  val simp by vm.simplified.collectAsStateWithLifecycle()
  val names = remember(members) { members.associate { it.id to it.name } }
  val snackbar = remember { SnackbarHostState() }
  val undoBar = remember { SnackbarHostState() }
  val undoOffer by vm.undoOffer.collectAsStateWithLifecycle()
  // Keyed on the offer itself, not on its text, so a rotation re-keys the effect and a
  // second delete re-runs it. The offer is captured here and passed back on the way out,
  // so the bar restores the expense it was shown for.
  LaunchedEffect(undoOffer) {
    val offer = undoOffer ?: return@LaunchedEffect
    when (undoBar.showSnackbar(offer.message, actionLabel = "Undo")) {
      // A timeout is a refusal. The user was shown the bar and did not take it,
      // so the offer is spent and the deletion stands for good.
      SnackbarResult.Dismissed -> vm.declineUndo(offer)
      SnackbarResult.ActionPerformed -> {
        if (!vm.undoLastDelete(offer)) snackbar.showSnackbar("Nothing to undo")
      }
    }
  }
  var detailOf by remember { mutableStateOf<ExpenseBundle?>(null) }
  var confirmSettleAll by remember { mutableStateOf(false) }
  val cur = g?.currencyCode ?: ""
  val activity by vm.feed.collectAsStateWithLifecycle()
  val spent = remember(expenses, rates, cur) { totalIn(expenses, cur, rates) }
  val timeFmt = remember {
    java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .withZone(java.time.ZoneId.systemDefault())
  }
  ChildShell(
      title = g?.name ?: "",
      onBack = onBack,
      actions = {
        IconButton(onClick = { addExpense(gid) }) { Icon(Icons.Default.Add, "Add expense") }
        IconButton(onClick = { g?.let { editGroup(it.id) } }) {
          Icon(Icons.Default.Edit, "Edit group")
        }
        Box {
          IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
          DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                { Text("History") },
                {
                  menu = false
                  history(gid)
                },
                leadingIcon = { Icon(Icons.Default.History, null) })
            DropdownMenuItem(
                { Text("Charts") },
                {
                  menu = false
                  charts(gid)
                })
            DropdownMenuItem(
                { Text("Recurring bills") },
                {
                  menu = false
                  recurring(gid)
                })
            DropdownMenuItem(
                { Text("Settings") },
                {
                  menu = false
                  settings()
                },
                leadingIcon = { Icon(Icons.Default.Settings, null) })
            DropdownMenuItem(
                { Text(if (g?.archived == true) "Unarchive group" else "Archive group") },
                {
                  menu = false
                  vm.setArchived(g?.archived != true) { onBack() }
                })
            DropdownMenuItem(
                { Text("Delete group") },
                {
                  menu = false
                  confirmDelete = true
                },
                leadingIcon = { Icon(Icons.Default.Delete, null) })
          }
        }
      },
  ) { p ->
    Scaffold(
        modifier = Modifier.padding(p),
        snackbarHost = {
        // The undo bar sits above the transient messages so they cannot push it off
        // screen: it is the one bar the user is meant to act on.
        Column(Modifier.fillMaxSize()) {
          Spacer(Modifier.weight(1f))
          SnackbarHost(undoBar)
          SnackbarHost(snackbar)
        }
      },
    ) { q ->
      Column(Modifier.fillMaxSize().padding(q)) {
        // The total rests on a rate for every foreign expense in the group, and where
        // one is missing the sum is `pickRate`'s parity figure -- a number invented
        // rather than computed. So the amount is withheld and the reason given.
        val unpriced = unconvertibleCurrencies(expenses, cur, rates)
        val foreign = expenses.any { it.currencyCode != cur }
        val count = if (expenses.size == 1) "1 expense" else "${expenses.size} expenses"
        Text(
            if (unpriced.isEmpty()) {
              listOfNotNull("${money(spent, cur)} total", count, "converted".takeIf { foreign })
                  .joinToString(" · ")
            } else "$count · ${unpricedNote(unpriced)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
        g?.selfMemberId?.let { self ->
          nets[self]?.let { net ->
            val label =
                when {
                  net > 0 -> "You are owed ${money(net, cur)} overall"
                  net < 0 -> "You owe ${money(-net, cur)} overall"
                  else -> "You are settled up"
                }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                  Text(
                      label,
                      style = MaterialTheme.typography.bodyMedium,
                      modifier = Modifier.padding(12.dp))
                }
          }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
          listOf("Expenses", "Balances", "Activity").forEachIndexed { i, t ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
          }
        }
        when (tab) {
          0 ->
              if (expenses.isEmpty())
                  Box(Modifier.fillMaxSize().padding(24.dp)) { Text("No expenses yet.") }
              else
                  LazyColumn {
                    items(expenses, key = { it.id }) { e ->
                      ListItem(
                          headlineContent = { Text(e.note ?: catLabel(e, customMap)) },
                          supportingContent = {
                            Text(
                                "${names[e.payerId] ?: "?"} paid ${money(e.amountMinor, e.currencyCode)}")
                          },
                          trailingContent = {
                            Row {
                              TextButton(onClick = { editExpense(gid, e.id) }) { Text("Edit") }
                              TextButton(
                                  onClick = {
                                    scope.launch { vm.deleteExpense(e.id) }
                                  }) {
                                    Text("Delete")
                                  }
                            }
                          },
                          modifier =
                              Modifier.clickable { scope.launch { detailOf = vm.bundle(e.id) } },
                      )
                      HorizontalDivider()
                    }
                  }
          1 ->
              LazyColumn(
                  Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Members (${members.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                          newAvatar = null
                          addOpen = true
                        }) { Text("+ Add") }
                      }
                    }
                    items(members, key = { it.id }) { m ->
                      val net = nets[m.id] ?: 0L
                      val per = native[m.id] ?: emptyMap()
                      ListItem(
                          headlineContent = { Text(m.name) },
                          supportingContent = {
                            Column {
                              Text(
                                  when {
                                    net > 0 -> "is owed ${money(net, cur)}"
                                    net < 0 -> "owes ${money(-net, cur)}"
                                    else -> "settled up"
                                  },
                                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                              )
                              per.toSortedMap().forEach { (code, amt) ->
                                // Every currency the member actually holds a balance
                                // in, including the group's own.
                                Text(
                                    (if (amt > 0) "+" else "") + money(amt, code),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                              }
                            }
                          },
                          leadingContent = { MemberAvatar(m.name, m.avatarPath) },
                          trailingContent = {
                            Row {
                              IconButton(
                                  onClick = {
                                    avatarFor = m.id
                                    pickImage()
                                  }) {
                                    Icon(Icons.Default.PhotoCamera, "Set photo for ${m.name}")
                                  }
                              IconButton(onClick = { removeOf = m.id }) {
                                Icon(Icons.Default.PersonRemove, "Remove ${m.name}")
                              }
                            }
                          },
                      )
                    }
                    item {
                      ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                              Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Currency rates (manual)",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { rateOpen = true }) { Text("+ Add") }
                              }
                              if (rates.isEmpty())
                                  Text(
                                      "No rates yet. Foreign expenses count 1:1 until you add one.",
                                      style = MaterialTheme.typography.bodySmall,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                              rates.forEach { r ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()) {
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
                    item {
                      ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                              Text("Split presets", style = MaterialTheme.typography.titleSmall)
                              OutlinedTextField(
                                  sgName,
                                  { sgName = it },
                                  label = { Text("Preset name (e.g. drinkers)") },
                                  singleLine = true,
                                  modifier = Modifier.fillMaxWidth())
                              members.forEach { m ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                      toggleMember(m.id)
                                    }) {
                                  Checkbox(sgSel.contains(m.id), { toggleMember(m.id) })
                                  Text(m.name, modifier = Modifier.weight(1f))
                                }
                              }
                              Button(
                                  onClick = {
                                    if (sgName.isNotBlank() && sgSel.isNotEmpty()) {
                                      vm.saveSubgroup(sgName, sgSel.toList())
                                      sgName = ""
                                      sgSel = emptySet()
                                    }
                                  },
                                  enabled = sgName.isNotBlank() && sgSel.isNotEmpty()) {
                                    Text("Save preset")
                                  }
                              subgroups.forEach { sg ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()) {
                                      Text(
                                          sg.name,
                                          modifier = Modifier.weight(1f),
                                          style = MaterialTheme.typography.bodyMedium)
                                      IconButton(onClick = { vm.deleteSubgroup(sg.id) }) {
                                        Icon(Icons.Default.Delete, "Delete ${sg.name}")
                                      }
                                    }
                              }
                            }
                      }
                    }
                    item {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Simplified debts", modifier = Modifier.weight(1f))
                        Switch(
                            simp,
                            {
                              vm.toggle(it)
                            })
                      }
                    }
                    if (plan.isEmpty()) item { Text("Everyone is settled.") }
                    else {
                      // The listed rows and the recorded ones are different sets
                      // whenever the toggle is off, and a user who settles each row by
                      // hand after reading this needs to know that.
                      if (settleAllCount != plan.size) {
                        item {
                          Text(
                              "Simplified. Settle all records $settleAllCount payments and clears every balance; the ${plan.size} listed below can also be settled one at a time.",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                      }
                      item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                          Button(
                              onClick = { confirmSettleAll = true },
                              modifier = Modifier.weight(1f)) {
                            // The count is settleAllCount, not plan.size: the toggle decides
                            // which plan is listed and the bulk settle always writes the
                            // simplified one, so quoting the visible rows counts payments
                            // this tap does not make.
                            Text("Settle all ($settleAllCount)")
                          }
                          OutlinedButton(
                              onClick = { share = true },
                              modifier = Modifier.weight(1f)) {
                                Text("Share")
                              }
                        }
                        if (confirmSettleAll)
                            AlertDialog(
                                onDismissRequest = { confirmSettleAll = false },
                                title = { Text("Settle all?") },
                                text = {
                                  // These payments come from nets converted at a
                                  // rate. Where one is missing the nets carry
                                  // parity, so the amounts here are the screen's
                                  // guess and the settle would refuse anyway.
                                  Text(
                                      if (unpriced.isEmpty())
                                          "This records $settleAllCount payments and clears all balances."
                                      else
                                          "Cannot settle everything: ${unpricedNote(unpriced)}. Add the rate first, then this records $settleAllCount payments and clears all balances.")
                                },
                                confirmButton = {
                                  TextButton(
                                      enabled = unpriced.isEmpty(),
                                      onClick = {
                                        scope.launch {
                                          val err = vm.settleAllAsync()
                                          confirmSettleAll = false
                                          if (err != null) snackbar.showSnackbar(err)
                                        }
                                      }) {
                                        Text("Settle all")
                                      }
                                },
                                dismissButton = {
                                  TextButton(onClick = { confirmSettleAll = false }) { Text("Cancel") }
                                })
                      }
                      items(plan, key = { "${it.from}-${it.to}-${it.currencyCode}" }) { t ->
                        ElevatedCard {
                          ListItem(
                              headlineContent = {
                                Text("${names[t.from] ?: "?"} pays ${names[t.to] ?: "?"}")
                              },
                              supportingContent = {
                                Text(money(t.amountMinor, t.currencyCode))
                              },
                              trailingContent = {
                                TextButton(
                                    onClick = { openSettle(t) }) {
                                      Text("Settle")
                                    }
                              })
                        }
                      }
                    }
                  }
          2 ->
              if (activity.isEmpty())
                  Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Nothing yet.") }
              else
                  LazyColumn {
                    items(activity, key = { it.id }) { ev ->
                      val icon =
                          when (ev.kind) {
                            EventKind.EXPENSE_ADDED -> Icons.Default.Add
                            EventKind.EXPENSE_UPDATED -> Icons.Default.Edit
                            EventKind.EXPENSE_DELETED -> Icons.Default.Delete
                            EventKind.EXPENSE_RESTORED -> Icons.AutoMirrored.Filled.Undo
                            EventKind.SETTLEMENT -> Icons.Default.CheckCircle
                            EventKind.MEMBER_ADDED -> Icons.Default.PersonAdd
                            EventKind.COMMENT_ADDED -> Icons.AutoMirrored.Filled.Comment
                            EventKind.RATE_SET -> Icons.Default.CurrencyExchange
                            else -> Icons.Default.PersonRemove
                          }
                      ListItem(
                          headlineContent = { Text(ev.summary) },
                          supportingContent = {
                            Text(timeFmt.format(java.time.Instant.ofEpochMilli(ev.timeEpoch)))
                          },
                          leadingContent = { Icon(icon, null) })
                      HorizontalDivider()
                    }
                  }
        }
      }
    }
  }
  // Resolved from the plan rather than held, so a rotation restores the row that was
  // tapped. A pair that has since left the plan has no row, and the dialog closes.
  val (settleFrom, settleTo) = settleOf.value
  val settleRow =
      if (settleFrom != 0L)
          plan.firstOrNull { it.from == settleFrom && it.to == settleTo }
      else null
  // A write in flight. The repository guard cannot see a second tap launched before
  // the first lands, so the button is disabled for the duration instead.
  var settling by remember { mutableStateOf(false) }
  settleRow?.let { s ->
    // A row in another currency cannot be settled until the rate that converts it
    // exists. SettleDialog would otherwise prefill pickRate's parity fallback -- EUR
    // 50.00 shown and recorded as USD 50.00, where the real figure is USD 54.50 --
    // under a label naming the group's currency, with nothing on screen to say the
    // number was invented.
    val priced =
        s.currencyCode.equals(cur, ignoreCase = true) ||
            lookupRate(rates, s.currencyCode, cur, System.currentTimeMillis()) != null
    if (priced) {
      SettleDialog(
          names[s.from] ?: "?",
          names[s.to] ?: "?",
          settlementAmountIn(s, cur, rates, System.currentTimeMillis()),
          cur,
          settleErr,
          { closeSettle(); settleErr = null },
          { amt, note ->
            if (settling) return@SettleDialog
            settling = true
            scope.launch {
              val err = vm.settle(s.from, s.to, amt, note)
              settling = false
              settleErr = err
              if (err == null) closeSettle()
            }
          },
          settling = settling)
    } else {
      UnpricedSettleNotice(
          names[s.from] ?: "?", names[s.to] ?: "?", s.currencyCode, cur, rateOf = { rateOpen = true })
    }
  }
  // Built on demand, not collected: a formatted report over the whole group that only
  // matters while the share sheet is open.
  var shareSummary by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(share, gid) { shareSummary = if (share) vm.shareSummary() else null }
  if (share && plan.isNotEmpty())
      SettleShareSheet(g?.name ?: "SplitSmart", plan, names, shareSummary) {
        share = false
      }
  detailOf?.let { b ->
    val thread by
        remember(b.expense.id) { vm.comments(b.expense.id) }
            .collectAsStateWithLifecycle(emptyList())
    ExpenseDetailDialog(
        b,
        names,
        cur,
        customMap,
        { detailOf = null },
        thread.size,
        {
          detailOf = null
          openExpense(gid, b.expense.id)
        })
  }
  if (addOpen) {
    var nm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { addOpen = false },
        title = { Text("Add member") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  MemberAvatar(nm.ifBlank { "?" }, newAvatar, 48.dp)
                  TextButton(onClick = { pickImage() }) {
                    Text(if (newAvatar == null) "Add photo" else "Change photo")
                  }
                }
            OutlinedTextField(nm, { nm = it }, label = { Text("Name") }, singleLine = true)
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                vm.addMember(nm, newAvatar)
                addOpen = false
              },
              enabled = nm.isNotBlank()) {
                Text("Add")
              }
        },
        dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } })
  }
  removeOf?.let { rid ->
    val nm = names[rid] ?: "this member"
    AlertDialog(
        onDismissRequest = { removeOf = null },
        title = { Text("Remove $nm?") },
        text = {
          Text(
              "You can only remove members with no balance and no history. Members with past expenses stay in the group.")
        },
        confirmButton = {
          TextButton(
              onClick = {
                scope.launch {
                  val err = vm.removeMember(rid)
                  snackbar.showSnackbar(err ?: "$nm removed")
                  removeOf = null
                }
              }) {
                Text("Remove")
              }
        },
        dismissButton = { TextButton(onClick = { removeOf = null }) { Text("Cancel") } })
  }
  if (rateOpen) {
    var from by remember { mutableStateOf("") }
    var to by remember(cur) { mutableStateOf(cur) }
    var rate by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { rateOpen = false },
        title = { Text("Add rate") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "1 unit of currency A = ? units of currency B.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                  from,
                  { from = it.uppercase().take(3) },
                  label = { Text("From") },
                  singleLine = true,
                  modifier = Modifier.weight(1f))
              OutlinedTextField(
                  to,
                  { to = it.uppercase().take(3) },
                  label = { Text("To") },
                  singleLine = true,
                  modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                rate,
                { rate = it },
                label = { Text("Rate") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth())
            err?.let {
              Text(
                  it,
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodySmall)
            }
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                scope.launch {
                  val r = parseRate(rate)
                  val problem =
                      if (from.length != 3 || to.length != 3) "Enter two 3-letter codes."
                      else if (r == null) "Enter a positive rate."
                      else
                          vm.saveRate(
                              FxRate(
                                  groupId = gid,
                                  fromCode = from,
                                  toCode = to,
                                  rate = r,
                                  timeEpoch = System.currentTimeMillis()))
                  if (problem == null) rateOpen = false else err = problem
                }
              }) {
                Text("Save")
              }
        },
        dismissButton = { TextButton(onClick = { rateOpen = false }) { Text("Cancel") } })
  }
  if (confirmDelete) {
    AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete group?") },
        text = { Text("Removes the group and all its expenses. This cannot be undone.") },
        confirmButton = {
          TextButton(onClick = { vm.deleteGroup { onBack() } }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
  }
}

@Composable
fun ExpenseDetailDialog(
    b: ExpenseBundle,
    names: Map<Long, String>,
    cur: String,
    customMap: Map<Long, CustomCategory>,
    onDismiss: () -> Unit,
    commentCount: Int = 0,
    onOpenComments: (() -> Unit)? = null
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(b.expense.note ?: b.expense.category.name) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
              "${money(b.expense.amountMinor, b.expense.currencyCode)} · ${catLabel(b.expense, customMap)}",
              style = MaterialTheme.typography.bodyMedium)
          HorizontalDivider()
          b.payments.forEach { (id, amt) ->
            Text(
                "${names[id] ?: "?"} paid ${money(amt, b.expense.currencyCode)}",
                style = MaterialTheme.typography.bodyMedium)
          }
          HorizontalDivider()
          b.shares.forEach { (id, amt) ->
            Text(
                "${names[id] ?: "?"} owes ${money(amt, b.expense.currencyCode)}",
                style = MaterialTheme.typography.bodyMedium)
          }
          b.items.forEach {
            Text(
                "· ${it.label}: ${money(it.amountMinor, b.expense.currencyCode)}",
                style = MaterialTheme.typography.bodySmall)
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
      dismissButton =
          onOpenComments?.let { open ->
            { TextButton(onClick = open) { Text("Comments ($commentCount)") } }
          })
}

@Composable
private fun UnpricedSettleNotice(
    from: String,
    to: String,
    rowCur: String,
    groupCur: String,
    rateOf: () -> Unit
) {
  AlertDialog(
      onDismissRequest = {},
      title = { Text("$from → $to") },
      text = {
        Text(
            "This payment is in $rowCur and the group settles in $groupCur. " +
                "No rate is entered for the pair, so there is no amount to record -- " +
                "entering one is the only way this can be settled.")
      },
      confirmButton = { TextButton(onClick = rateOf) { Text("Add rate") } })
}

@Composable
fun SettleDialog(
    from: String,
    to: String,
    default: Long,
    cur: String,
    error: String? = null,
    dismiss: () -> Unit,
    save: (Long, String?) -> Unit,
    settling: Boolean = false
) {
  // Integer-only, and the same formatter the payment links use: `majorToMinor` parses
  // this text straight back on Record, so a Double here loses a cent above 2^53 and
  // prints "12.5" where the rest of the app prints "12.50". Re-keyed on the transfer so
  // opening a different row starts from that row's own default.
  var amt by rememberSaveable(from, to, default) { mutableStateOf(minorToDecimal(default)) }
  var note by rememberSaveable(from, to, default) { mutableStateOf("") }
  AlertDialog(
      onDismissRequest = dismiss,
      title = { Text("$from → $to") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(amt, { amt = it }, label = { Text("Amount ($cur)") }, singleLine = true)
          OutlinedTextField(
              note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
          // Held here because the dialog is what the user is looking at when a payment
          // is refused, and a balance that did not move is otherwise indistinguishable
          // from a write that silently did nothing.
          error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
          }
        }
      },
      confirmButton = {
        val cents = majorToMinor(amt)?.takeIf { it > 0 } ?: -1L
        TextButton(
            onClick = { if (cents > 0) save(cents, note) }, enabled = cents > 0 && !settling) {
          Text("Record")
        }
      },
      dismissButton = { TextButton(onClick = dismiss, enabled = !settling) { Text("Cancel") } })
}

@Composable
private fun MemberAvatar(name: String, path: String?, size: androidx.compose.ui.unit.Dp = 40.dp) {
  if (path != null) {
    AsyncImage(
        model = java.io.File(path),
        contentDescription = "$name photo",
        modifier = Modifier.size(size).clip(CircleShape),
        contentScale = ContentScale.Crop,
    )
  } else {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(size)) {
          Box(contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
          }
        }
  }
}
