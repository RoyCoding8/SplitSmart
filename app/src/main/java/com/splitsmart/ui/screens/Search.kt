package com.splitsmart.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.ui.shell.DrawerDest
import com.splitsmart.ui.shell.TopLevelShell
import com.splitsmart.ui.vm.SearchVm
import com.splitsmart.ui.vm.money

@Composable
fun SearchScreen(
    openGroup: (Long) -> Unit = {},
    openExpense: (Long, Long) -> Unit = { _, _ -> },
    onOpenGroups: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    vm: SearchVm = hiltViewModel(),
) {
  val q by vm.query.collectAsStateWithLifecycle()
  val hits by vm.hits.collectAsStateWithLifecycle()
  val groups by vm.groups.collectAsStateWithLifecycle()
  val names = remember(groups) { groups.associate { it.id to it.name } }
  TopLevelShell(
      title = "Search",
      selected = DrawerDest.Search,
      onOpenGroups = onOpenGroups,
      onOpenStats = onOpenStats,
      onOpenSettings = onOpenSettings) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                  value = q,
                  onValueChange = vm::query,
                  label = { Text("Search expenses, people, groups") },
                  leadingIcon = { Icon(Icons.Default.Search, "Search") },
                  trailingIcon = {
                    if (q.isNotEmpty())
                        IconButton({ vm.query("") }) { Icon(Icons.Default.Clear, "Clear search") }
                  },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth(),
              )
              if (q.isBlank()) {
                Text(
                    "Try a note, category, name, or group.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
              } else if (hits.expenses.isEmpty() &&
                  hits.members.isEmpty() &&
                  hits.groups.isEmpty()) {
                Text("No results for \"$q\".", style = MaterialTheme.typography.bodyMedium)
              } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  if (hits.expenses.isNotEmpty()) {
                    item { Text("Expenses", style = MaterialTheme.typography.titleSmall) }
                    items(hits.expenses, key = { "e${it.id}" }) { e ->
                      ListItem(
                          headlineContent = {
                            Text(
                                e.note?.takeIf { it.isNotBlank() }
                                    ?: e.category.name.lowercase().replaceFirstChar {
                                      it.uppercase()
                                    })
                          },
                          supportingContent = {
                            Text(
                                "${names[e.groupId] ?: ""} · ${money(e.amountMinor, e.currencyCode)}")
                          },
                          modifier = Modifier.clickable { openExpense(e.groupId, e.id) },
                      )
                    }
                  }
                  if (hits.members.isNotEmpty()) {
                    item { Text("People", style = MaterialTheme.typography.titleSmall) }
                    items(hits.members, key = { "m${it.id}" }) { m ->
                      ListItem(
                          headlineContent = { Text(m.name) },
                          supportingContent = { Text(names[m.groupId] ?: "") },
                          modifier = Modifier.clickable { openGroup(m.groupId) },
                      )
                    }
                  }
                  if (hits.groups.isNotEmpty()) {
                    item { Text("Groups", style = MaterialTheme.typography.titleSmall) }
                    items(hits.groups, key = { "g${it.id}" }) { g ->
                      ListItem(
                          headlineContent = { Text(g.name) },
                          supportingContent = { Text(g.currencyCode) },
                          modifier = Modifier.clickable { openGroup(g.id) },
                      )
                    }
                  }
                }
              }
            }
      }
}
