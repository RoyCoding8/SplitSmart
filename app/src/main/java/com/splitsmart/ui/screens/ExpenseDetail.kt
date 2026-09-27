package com.splitsmart.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.data.ExpenseBundle
import com.splitsmart.ui.shell.ChildShell
import com.splitsmart.ui.vm.GroupDetailVm
import com.splitsmart.ui.vm.money
import kotlinx.coroutines.launch

@Composable
fun ExpenseDetailScreen(
    gid: Long,
    eid: Long,
    editExpense: (Long, Long) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    vm: GroupDetailVm = hiltViewModel()
) {
  LaunchedEffect(gid) { vm.bind(gid) }
  val g by vm.group.collectAsStateWithLifecycle()
  val members by vm.members.collectAsStateWithLifecycle()
  val expenses by vm.expenses.collectAsStateWithLifecycle()
  val thread by remember(eid) { vm.comments(eid) }.collectAsStateWithLifecycle(emptyList())
  var bundle by remember { mutableStateOf<ExpenseBundle?>(null) }
  LaunchedEffect(eid) { bundle = vm.bundle(eid) }
  // The draft is the user's typing, so it crosses a rotation and it survives a rejected
  // post, which keeps a failure retryable rather than retypable.
  var draft by rememberSaveable { mutableStateOf("") }
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val names = remember(members) { members.associate { it.id to it.name } }
  val ex = expenses.firstOrNull { it.id == eid }
  // Only the group's self identity can author a comment. There is no fallback to another
  // member: a comment is a record of who said something, and naming the wrong person is
  // worse than naming none.
  val author = g?.selfMemberId?.takeIf { id -> members.any { it.id == id } }

  val timeFmt = remember {
    java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .withZone(java.time.ZoneId.systemDefault())
  }
  ChildShell(
      title = ex?.note?.takeIf { it.isNotBlank() } ?: ex?.category?.name ?: "Expense",
      onBack = onBack,
      actions = {
        IconButton(onClick = { editExpense(gid, eid) }) { Icon(Icons.Default.Edit, "Edit expense") }
      },
  ) { p ->
    if (ex == null) {
      Box(Modifier.fillMaxSize().padding(p).padding(24.dp)) { Text("Expense not found.") }
    } else {
      Column(Modifier.fillMaxSize().padding(p)) {
        LazyColumn(
            Modifier.weight(1f).padding(16.dp, 16.dp, 16.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
              item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                  Column(
                      Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            money(ex.amountMinor, ex.currencyCode),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Paid by ${names[ex.payerId] ?: "?"} · ${ex.category.name}",
                            style = MaterialTheme.typography.bodyMedium)
                        bundle?.let { b ->
                          HorizontalDivider()
                          b.shares.forEach { (id, amt) ->
                            Text(
                                "${names[id] ?: "?"} owes ${money(amt, ex.currencyCode)}",
                                style = MaterialTheme.typography.bodyMedium)
                          }
                          if (ex.receiptUri != null)
                              Text(
                                  "Receipt attached",
                                  style = MaterialTheme.typography.bodySmall,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                      }
                }
              }
              item {
                Text("Comments (${thread.size})", style = MaterialTheme.typography.titleMedium)
              }
              if (thread.isEmpty())
                  item {
                    Text(
                        "No comments yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
              items(thread, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.text) },
                    supportingContent = {
                      Text(
                          "${names[c.authorId] ?: "Someone"} · ${timeFmt.format(java.time.Instant.ofEpochMilli(c.timeEpoch))}")
                    },
                    trailingContent = {
                      IconButton(onClick = { vm.deleteComment(c.id) }) {
                        Icon(Icons.Default.Delete, "Delete comment")
                      }
                    },
                )
                HorizontalDivider()
              }
            }
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                  draft,
                  { draft = it },
                  label = { Text("Add a comment") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                  enabled = author != null)
              IconButton(
                  onClick = {
                    author?.let { id ->
                      scope.launch {
                        val err = vm.addComment(eid, id, draft)
                        if (err == null) draft = ""
                        else snackbar.showSnackbar("Could not post that comment")
                      }
                    }
                  },
                  enabled = author != null && draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send comment")
                  }
            }
        SnackbarHost(snackbar)
      }
    }
  }
}
