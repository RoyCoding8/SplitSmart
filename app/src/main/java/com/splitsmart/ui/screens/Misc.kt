package com.splitsmart.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.data.*
import com.splitsmart.ui.shell.ChildShell
import com.splitsmart.ui.vm.GroupDetailVm
import com.splitsmart.ui.vm.majorToMinor
import com.splitsmart.ui.vm.money
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(gid: Long, onBack: () -> Unit = {}, vm: GroupDetailVm = hiltViewModel()) {
  LaunchedEffect(gid) { vm.bind(gid) }
  val expenses by vm.expenses.collectAsStateWithLifecycle()
  val settles by vm.settles.collectAsStateWithLifecycle()
  var q by remember { mutableStateOf("") }
  var cat by remember { mutableStateOf<Category?>(null) }
  val g by vm.group.collectAsStateWithLifecycle()
  val cur = g?.currencyCode ?: ""
  val customs by vm.customs.collectAsStateWithLifecycle()
  val customMap = remember(customs) { customs.associateBy { it.id } }
  val ex =
      remember(expenses, q, cat, customMap) {
        expenses.filter {
          (cat == null || it.category == cat) && historyMatches(it, q, customMap)
        }
      }
  val shownSettles =
      remember(settles, q, cat) {
        if (cat != null) emptyList()
        else settles.filter { q.isBlank() || (it.note ?: "").contains(q, true) }
      }
  ChildShell(title = "History", onBack = onBack) { p ->
    Column(
        Modifier.fillMaxSize().padding(p).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
              q,
              { q = it },
              label = { Text("Search") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth())
          Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(cat == null, { cat = null }, label = { Text("All") })
                Category.entries.forEach { c ->
                  FilterChip(
                      cat == c, { cat = if (cat == c) null else c }, label = { Text(c.name) })
                }
              }
          LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ex, key = { "e${it.id}" }) { e ->
              ListItem(
                  headlineContent = { Text(e.note ?: catLabel(e, customMap)) },
                  supportingContent = { Text(money(e.amountMinor, e.currencyCode)) })
            }
            items(shownSettles, key = { "s${it.id}" }) { s ->
              ListItem(
                  headlineContent = {
                    Text("Settlement ${money(s.amountMinor, cur)}")
                  },
                  supportingContent = { s.note?.let { n -> Text(n) } })
            }
          }
        }
  }
}

@Composable
fun RecurringScreen(gid: Long, onBack: () -> Unit = {}, vm: GroupDetailVm = hiltViewModel()) {
  LaunchedEffect(gid) { vm.bind(gid) }
  val templates by vm.templates.collectAsStateWithLifecycle()
  val members by vm.members.collectAsStateWithLifecycle()
  val g by vm.group.collectAsStateWithLifecycle()
  val cur = g?.currencyCode ?: ""
  val names = remember(members) { members.associate { it.id to it.name } }
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var show by remember { mutableStateOf(false) }
  val due =
      remember(templates) {
        templates.count { it.active && it.nextDueEpoch <= System.currentTimeMillis() }
      }
  ChildShell(title = "Recurring bills", onBack = onBack) { p ->
    Scaffold(
        modifier = Modifier.padding(p),
        floatingActionButton = {
          FloatingActionButton(onClick = { show = true }) {
            Icon(Icons.Default.Add, "add template")
          }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { q ->
      Column(
          Modifier.fillMaxSize().padding(q).padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (due > 0)
                Button(
                    onClick = {
                      scope.launch {
                        val n = vm.generateDue()
                        snackbar.showSnackbar(
                            if (n == 0) "Nothing due"
                            else "Generated $n expense${if (n == 1) "" else "s"}")
                      }
                    },
                    modifier = Modifier.fillMaxWidth()) {
                      Text("Generate $due due")
                    }
            if (templates.isEmpty())
                Box(Modifier.fillMaxWidth().padding(24.dp)) {
                  Text("No recurring bills yet. Add one for rent, utilities, or subscriptions.")
                }
            else
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  items(templates, key = { it.id }) { t ->
                    ElevatedCard {
                      ListItem(
                          headlineContent = {
                            Text("${t.note ?: t.category.name} · ${t.frequency.name.lowercase()}")
                          },
                          supportingContent = {
                            Text(
                                "${names[t.payerId] ?: "?"} pays ${money(t.amountMinor, cur)} · next ${java.time.Instant.ofEpochMilli(t.nextDueEpoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}")
                          },
                          trailingContent = {
                            Row {
                              TextButton(onClick = { vm.toggleTemplate(t) }) {
                                Text(if (t.active) "Pause" else "Resume")
                              }
                              TextButton(onClick = { vm.deleteTemplate(t.id) }) { Text("Delete") }
                            }
                          },
                      )
                    }
                  }
                }
          }
    }
  }
  if (show)
      TemplateDialog(
          members,
          cur,
          { show = false },
          { payer, amt, cat, freq, note ->
            vm.saveTemplate(
                RecurringTemplate(
                    groupId = gid,
                    payerId = payer,
                    amountMinor = amt,
                    category = cat,
                    note = note?.ifBlank { null },
                    splitRule = SplitRepository.encodeRule(SplitType.EQUAL, mapOf(), mapOf()),
                    frequency = freq,
                    nextDueEpoch = System.currentTimeMillis()))
            show = false
          })
}

@Composable
private fun TemplateDialog(
    members: List<Member>,
    cur: String,
    dismiss: () -> Unit,
    save: (Long, Long, Category, Frequency, String?) -> Unit
) {
  var payer by remember(members) { mutableStateOf(members.firstOrNull()?.id ?: -1L) }
  var amt by remember { mutableStateOf("") }
  var cat by remember { mutableStateOf(Category.RENT) }
  var freq by remember { mutableStateOf(Frequency.MONTHLY) }
  var note by remember { mutableStateOf("") }
  // majorToMinor, not hand-rolled Double math: Math.round(it * 100) silently wraps,
  // and "1e20" parses fine as a Double and lands on a positive amount with Save
  // enabled. BigDecimal rejects it, and it is the same parser the expense editor
  // already uses, so the two cannot drift.
  val total = majorToMinor(amt) ?: 0L
  AlertDialog(
      onDismissRequest = dismiss,
      title = { Text("Recurring bill") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (members.isEmpty())
              Text(
                  "Add members to the group first. Every expense needs a payer.",
                  color = MaterialTheme.colorScheme.error)
          else
              Row(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    members.forEach { m ->
                      FilterChip(payer == m.id, { payer = m.id }, label = { Text(m.name) })
                    }
                  }
          OutlinedTextField(amt, { amt = it }, label = { Text("Amount ($cur)") }, singleLine = true)
          Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(Category.RENT, Category.FOOD, Category.TRAVEL, Category.OTHER).forEach { c ->
                  FilterChip(cat == c, { cat = c }, label = { Text(c.name) })
                }
              }
          Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Frequency.entries.forEach { f ->
                  FilterChip(freq == f, { freq = f }, label = { Text(f.name.lowercase()) })
                }
              }
          OutlinedTextField(
              note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
          Text(
              "Generated bills split equally among all current members.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      },
      confirmButton = {
        TextButton(
            onClick = { save(payer, total, cat, freq, note) },
            enabled = payer != -1L && total > 0) {
              Text("Save")
            }
      },
      dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
