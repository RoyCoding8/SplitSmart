package com.splitsmart.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.data.format
import com.splitsmart.data.sumByCurrency
import com.splitsmart.ui.shell.DrawerDest
import com.splitsmart.ui.shell.TopLevelShell
import com.splitsmart.ui.vm.StatsVm

@Composable
fun StatsScreen(
    openGroup: (Long) -> Unit = {},
    onOpenGroups: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    openSearch: () -> Unit = {},
    vm: StatsVm = hiltViewModel(),
) {
  val groups by vm.groups.collectAsStateWithLifecycle()
  val expenses by vm.expenses.collectAsStateWithLifecycle()
  val totals =
      remember(expenses) {
        expenses.groupBy { it.groupId }.mapValues { (_, v) -> v.sumByCurrency() }
      }
  val counts = remember(expenses) { expenses.groupBy { it.groupId }.mapValues { (_, v) -> v.size } }
  TopLevelShell(
      title = "Stats",
      selected = DrawerDest.Stats,
      onOpenGroups = onOpenGroups,
      onOpenSettings = onOpenSettings,
      onOpenSearch = openSearch,
  ) { p ->
    LazyColumn(
        Modifier.fillMaxSize().padding(p).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
          item {
            ElevatedCard(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Overview", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Groups", style = MaterialTheme.typography.bodyMedium)
                  Text(groups.size.toString(), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text("Expenses", style = MaterialTheme.typography.bodyMedium)
                  Text(expenses.size.toString(), style = MaterialTheme.typography.bodyMedium)
                }
              }
            }
          }
          if (groups.isEmpty()) {
            item {
              Text(
                  "No groups yet. Your stats will show up here.", modifier = Modifier.padding(8.dp))
            }
          } else {
            item { Text("Spend per group", style = MaterialTheme.typography.titleMedium) }
            items(groups, key = { it.id }) { g ->
              ListItem(
                  headlineContent = { Text(g.name) },
                  supportingContent = { Text("${counts[g.id] ?: 0} expenses · ${g.currencyCode}") },
                  trailingContent = {
                    Text(totals[g.id].orEmpty().format(g.currencyCode))
                  },
                  modifier = Modifier.clickable { openGroup(g.id) },
              )
              HorizontalDivider()
            }
          }
        }
  }
}
