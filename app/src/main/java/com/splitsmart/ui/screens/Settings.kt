package com.splitsmart.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.data.CustomCategory
import com.splitsmart.data.ThemeMode
import com.splitsmart.ui.shell.DrawerDest
import com.splitsmart.ui.shell.TopLevelShell
import com.splitsmart.ui.shell.appVersion
import com.splitsmart.ui.vm.SettingsVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onOpenGroups: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    openSearch: () -> Unit = {},
    vm: SettingsVm = hiltViewModel()
) {
  val prefs by vm.settings.collectAsStateWithLifecycle()
  val groups by vm.groups.collectAsStateWithLifecycle()
  val customs by vm.customs.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current
  val snackbar = remember { SnackbarHostState() }
  var currency by rememberSaveable(prefs.defaultCurrency) { mutableStateOf(prefs.defaultCurrency) }
  var msg by remember { mutableStateOf<String?>(null) }
  // Read from the system, not from the stored preference: the preference says the
  // user asked for reminders, the system decides whether any can be delivered.
  fun canPostNotifications(): Boolean =
      Build.VERSION.SDK_INT < 33 ||
          ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
              PackageManager.PERMISSION_GRANTED
  val billNotifsOn = prefs.billNotifs && canPostNotifications()
  var csvOpen by remember { mutableStateOf(false) }
  var importOpen by remember { mutableStateOf(false) }
  var importName by rememberSaveable { mutableStateOf("") }
  var importCur by rememberSaveable(prefs.defaultCurrency) { mutableStateOf(prefs.defaultCurrency) }
  var catName by rememberSaveable { mutableStateOf("") }
  var catEmoji by rememberSaveable { mutableStateOf("") }
  fun readPicked(uri: android.net.Uri?, onRead: suspend (String) -> Unit) {
    scope.launch {
      // A cancelled picker hands back null, which openInputStream does not accept. Read
      // on IO because a backup base64s every receipt and comes to hundreds of megabytes
      // on a group with real ones.
      val text =
          if (uri == null) null
          else
              withContext(Dispatchers.IO) {
                runCatching {
                      ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    }
                    .getOrNull()
              }
      if (text == null) snackbar.showSnackbar("Could not read that file") else onRead(text)
    }
  }
  val csvPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        readPicked(uri) { csv ->
          importOpen = false
          snackbar.showSnackbar(
              vm.importCsv(importName, importCur, csv)
                  .fold(
                      { r ->
                        val n = r.imported
                        val head =
                            "Imported $n ${if (n == 1) "row" else "rows"} into ${importName.ifBlank { "Imported" }}"
                        if (r.skipped.isEmpty()) head
                        else "$head, ${r.skipped.size} skipped: ${r.skipped.first()}"
                      },
                      { "Import failed: ${it.message}" }))
        }
      }

  fun share(text: String, title: String) {
    ctx.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_TEXT, text)
            },
            title))
  }
  val notifPerm =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setBillNotifs(true)
        else msg = "Notifications were declined, so reminders will not arrive."
      }
  val restorePicker =
      rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        readPicked(uri) { json ->
          snackbar.showSnackbar(
              vm.restoreJson(json)
                  .fold(
                      { r ->
                        val n = r.restored
                        val head = "Restored $n ${if (n == 1) "expense" else "expenses"}"
                        val lost =
                            if (r.skipped.isEmpty()) head
                            else "$head, ${r.skipped.size} skipped: ${r.skipped.first()}"
                        if (r.duplicated.isEmpty()) lost
                        else
                          "$lost. Already had ${r.duplicated.size} ${if (r.duplicated.size == 1) "group" else "groups"} from this file: ${r.duplicated.joinToString(", ")}"
                      },
                      { "Restore failed: ${it.message}" }))
        }
      }
  TopLevelShell(
      title = "Settings",
      selected = DrawerDest.Settings,
      onOpenGroups = onOpenGroups,
      onOpenStats = onOpenStats,
      onOpenSearch = openSearch) { p ->
        Scaffold(modifier = Modifier.padding(p), snackbarHost = { SnackbarHost(snackbar) }) { q ->
          LazyColumn(
              Modifier.fillMaxSize().padding(q).padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { SectionTitle("Appearance") }
                item {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                      FilterChip(
                          prefs.themeMode == m,
                          { vm.setThemeMode(m) },
                          label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                  }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Text("Dynamic color", modifier = Modifier.weight(1f))
                      Switch(prefs.dynamicColor, { vm.setDynamicColor(it) })
                    }
                  }
                }
                item { SectionTitle("Defaults") }
                item {
                  Row(
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            currency,
                            { currency = it.uppercase().take(3); msg = null },
                            label = { Text("Default currency (ISO)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { vm.setDefaultCurrency(currency) { m -> msg = m } },
                            enabled = currency.length == 3) {
                              Text("Save")
                            }
                      }
                      msg?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                      }
                }
                item { SectionTitle("Notifications") }
                item {
                  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bill & settle-up reminders", modifier = Modifier.weight(1f))
                    Switch(
                        billNotifsOn,
                        {
                          if (it &&
                              Build.VERSION.SDK_INT >= 33 &&
                              ActivityCompat.checkSelfPermission(
                                  ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                                  PackageManager.PERMISSION_GRANTED)
                              notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                          else vm.setBillNotifs(it)
                        })
                  }
                }
                if (billNotifsOn && !canPostNotifications())
                  item {
                    Text(
                        "Reminders are on but this device has not allowed notifications, so none will arrive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                  }
                item { SectionTitle("Categories") }
                if (customs.isEmpty())
                    item {
                      Text(
                          "Built-in categories only. Add your own below.",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                customs.forEach { c ->
                  item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                          Text("${c.emoji} ${c.name}", modifier = Modifier.weight(1f))
                          IconButton(
                              onClick = {
                                scope.launch {
                                  vm.deleteCustomCat(c.id).let { err ->
                                    snackbar.showSnackbar(err ?: "Category deleted")
                                  }
                                }
                              }) {
                                Icon(Icons.Default.Delete, "Delete ${c.name}")
                              }
                        }
                  }
                }
                item {
                  Row(
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            catEmoji,
                            { catEmoji = it.take(8) },
                            label = { Text("Emoji") },
                            singleLine = true,
                            modifier = Modifier.width(96.dp))
                        OutlinedTextField(
                            catName,
                            { catName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                              scope.launch {
                                vm.saveCustomCat(CustomCategory(name = catName, emoji = catEmoji))
                                    .let { err ->
                                      if (err == null) {
                                        catName = ""
                                        catEmoji = ""
                                      }
                                      snackbar.showSnackbar(err ?: "Category added")
                                    }
                              }
                            },
                            enabled = catName.isNotBlank()) {
                              Text("Add")
                            }
                      }
                }
                item { SectionTitle("Data") }
                item {
                  OutlinedButton(
                      onClick = { scope.launch { share(vm.backupJson(), "SplitSmart backup") } },
                      modifier = Modifier.fillMaxWidth()) {
                        Text("Backup now")
                      }
                }
                item {
                  OutlinedButton(
                      onClick = { restorePicker.launch("application/json") },
                      modifier = Modifier.fillMaxWidth()) {
                        Text("Restore from file")
                      }
                }
                item {
                  OutlinedButton(
                      onClick = { csvOpen = true },
                      enabled = groups.isNotEmpty(),
                      modifier = Modifier.fillMaxWidth()) {
                        Text("Export group as CSV")
                      }
                }
                item {
                  OutlinedButton(
                      onClick = { importOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Import group from CSV")
                      }
                }
                item { SectionTitle("About") }
                item {
                  Text(
                      "SplitSmart ${appVersion()?.let { "v$it" } ?: ""}",
                      style = MaterialTheme.typography.titleMedium)
                }
                item {
                  Text(
                      "Bill splitting and settle-up math that runs 100% offline. Apache-2.0.",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
        }
      }
  if (csvOpen)
      AlertDialog(
          onDismissRequest = { csvOpen = false },
          title = { Text("Export group as CSV") },
          text = {
            Column {
              groups.forEach { g ->
                ListItem(
                    headlineContent = { Text(g.name) },
                    supportingContent = { Text(g.currencyCode) },
                    modifier =
                        Modifier.clickable {
                          csvOpen = false
                          scope.launch { share(vm.groupCsv(g.id), "Export ${g.name}") }
                        })
              }
            }
          },
          confirmButton = { TextButton(onClick = { csvOpen = false }) { Text("Cancel") } },
      )
  if (importOpen)
      AlertDialog(
          onDismissRequest = { importOpen = false },
          title = { Text("Import group from CSV") },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                  importName,
                  { importName = it },
                  label = { Text("New group name") },
                  singleLine = true)
              OutlinedTextField(
                  importCur,
                  { importCur = it.uppercase().take(3) },
                  label = { Text("Currency (ISO)") },
                  singleLine = true)
              Text(
                  "SplitSmart CSV flavor: type,payer,amount_minor,currency,category,note,date,details.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          },
          confirmButton = {
            TextButton(onClick = { csvPicker.launch("text/csv") }) { Text("Choose file") }
          },
          dismissButton = { TextButton(onClick = { importOpen = false }) { Text("Cancel") } },
      )
}

@Composable
private fun SectionTitle(t: String) {
  Text(
      t,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}
