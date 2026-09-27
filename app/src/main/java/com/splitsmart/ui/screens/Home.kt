package com.splitsmart.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.data.Group
import com.splitsmart.data.Member
import com.splitsmart.data.unpricedNote
import com.splitsmart.ui.shell.ChildShell
import com.splitsmart.ui.shell.DrawerDest
import com.splitsmart.ui.shell.TopLevelShell
import com.splitsmart.ui.vm.GroupEditVm
import com.splitsmart.ui.vm.HomeVm
import com.splitsmart.ui.vm.Load
import com.splitsmart.ui.vm.groupContainer
import com.splitsmart.ui.vm.money
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    openGroup: (Long) -> Unit,
    addGroup: () -> Unit,
    settings: () -> Unit = {},
    openStats: () -> Unit = {},
    defaultCurrency: String = "USD",
    openFriend: (Long) -> Unit = openGroup,
    openSearch: () -> Unit = {},
    vm: HomeVm = hiltViewModel()
) {
  val trips by vm.tripGroups.collectAsStateWithLifecycle()
  val friends by vm.friendGroups.collectAsStateWithLifecycle()
  val archived by vm.archivedGroups.collectAsStateWithLifecycle()
  val dash by vm.dashboard.collectAsStateWithLifecycle()
  var friend by remember { mutableStateOf(false) }
  var choose by remember { mutableStateOf(false) }
  var tab by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()
  TopLevelShell(
      title = "SplitSmart",
      selected = DrawerDest.Groups,
      centered = true,
      actions = { IconButton(settings) { Icon(Icons.Default.Settings, "Settings") } },
      onOpenSettings = settings,
      onOpenStats = openStats,
      onOpenSearch = openSearch,
  ) { p ->
    Scaffold(
        modifier = Modifier.padding(p),
        floatingActionButton = {
          Column(
              horizontalAlignment = Alignment.End,
              verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(
                    visible = choose,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()) {
                      Column(
                          horizontalAlignment = Alignment.End,
                          verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                  Text("New group", style = MaterialTheme.typography.labelLarge)
                                  SmallFloatingActionButton(
                                      onClick = {
                                        choose = false
                                        addGroup()
                                      }) {
                                        Icon(Icons.Default.Group, "New group")
                                      }
                                }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                  Text("Add friend", style = MaterialTheme.typography.labelLarge)
                                  SmallFloatingActionButton(
                                      onClick = {
                                        choose = false
                                        friend = true
                                      }) {
                                        Icon(Icons.Default.PersonAdd, "Add friend")
                                      }
                                }
                          }
                    }
                FloatingActionButton(onClick = { choose = !choose }) {
                  Icon(
                      if (choose) Icons.Default.Close else Icons.Default.Add,
                      if (choose) "Close" else "Add")
                }
              }
        },
    ) { q ->
      Column(Modifier.fillMaxSize().padding(q)) {
        when (val d = dash) {
          is Load.Ok ->
              if (d.v.isNotEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth().padding(8.dp, 8.dp, 8.dp, 0.dp)) {
                  Column(
                      Modifier.padding(16.dp, 12.dp),
                      verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Across your groups",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        d.v.forEach { r ->
                          val note = unpricedNote(r.unpriced)
                          if (r.owed > 0)
                              Row(
                                  Modifier.fillMaxWidth(),
                                  horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        "You are owed", style = MaterialTheme.typography.bodyMedium)
                                    // A currency with no rate is not owed a smaller amount, it
                                    // is owed an amount nobody has computed, so the reason is
                                    // given instead.
                                    if (note.isEmpty())
                                        Text(
                                            money(r.owed, r.currency),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.tertiary)
                                  }
                          if (r.owe > 0)
                              Row(
                                  Modifier.fillMaxWidth(),
                                  horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("You owe", style = MaterialTheme.typography.bodyMedium)
                                    if (note.isEmpty())
                                        Text(
                                            money(r.owe, r.currency),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error)
                                  }
                          if (note.isNotEmpty())
                              Text(
                                  note,
                                  style = MaterialTheme.typography.bodySmall,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                      }
                }
              }
          is Load.Busy,
          is Load.Err -> {}
        }
        PrimaryTabRow(tab) {
          Tab(tab == 0, { tab = 0 }, text = { Text("Groups") })
          Tab(tab == 1, { tab = 1 }, text = { Text("Friends") })
          Tab(tab == 2, { tab = 2 }, text = { Text("Archived") })
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
              val s = if (tab == 0) trips else if (tab == 1) friends else archived
              when (s) {
                is Load.Ok ->
                    if (s.v.isEmpty())
                        item {
                          Text(
                              if (tab == 0) "No groups yet. Tap + to create one."
                              else if (tab == 1) "No friends yet. Tap + to add one."
                              else "Nothing archived.",
                              modifier = Modifier.padding(8.dp))
                        }
                    else
                        items(s.v, key = { "${tab}-${it.id}" }) { g ->
                          GroupRow(
                              g,
                              if (tab == 1) openFriend else openGroup,
                              if (tab == 2)
                                  ({
                                    TextButton(onClick = { vm.unarchive(g) }) { Text("Restore") }
                                  })
                              else null)
                        }
                is Load.Busy ->
                    item { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
                is Load.Err -> item { Text(s.m, modifier = Modifier.padding(8.dp)) }
              }
            }
      }
    }
  }
  if (choose) BackHandler { choose = false }
  if (friend) {
    var nm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { friend = false },
        title = { Text("Add friend") },
        text = {
          OutlinedTextField(nm, { nm = it }, label = { Text("Friend name") }, singleLine = true)
        },
        confirmButton = {
          TextButton(
              onClick = {
                scope.launch {
                  val id = vm.addFriend(nm, defaultCurrency)
                  friend = false
                  openFriend(id)
                }
              },
              enabled = nm.isNotBlank()) {
                Text("Add")
              }
        },
        dismissButton = { TextButton(onClick = { friend = false }) { Text("Cancel") } })
  }
}

@Composable
private fun GroupRow(
    g: Group,
    openGroup: (Long) -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
  ListItem(
      headlineContent = { Text(g.name) },
      supportingContent = { Text(g.currencyCode) },
      leadingContent = {
        Surface(
            shape = CircleShape, color = groupContainer(g.color), modifier = Modifier.size(40.dp)) {
              Box(contentAlignment = Alignment.Center) {
                Text(g.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
              }
            }
      },
      trailingContent = trailing,
      modifier = Modifier.clickable { openGroup(g.id) },
  )
  HorizontalDivider()
}

@Composable
private fun ColorDots(selected: Int, onPick: (Int) -> Unit) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically) {
        (0..4).forEach { i ->
          Surface(
              shape = CircleShape,
              color = groupContainer(i),
              modifier =
                  Modifier.size(40.dp)
                      .clickable(
                          role = Role.RadioButton,
                          onClickLabel = "Group color ${i + 1}",
                          onClick = { onPick(i) },
                      ),
              border =
                  if (i == selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                  else null,
          ) {
            if (i == selected)
                Box(contentAlignment = Alignment.Center) {
                  Icon(Icons.Default.Check, contentDescription = "Selected color")
                }
          }
        }
      }
}

/**
 * The editor's member rows, flattened for the saved instance state.
 *
 * Everything a `Member` carries beyond its id and name is dropped on purpose: a restored
 * row is a stub, and a stub must never reach a write, since saving writes the whole entity
 * for a row with an id.
 */
private val memberListSaver =
    listSaver<SnapshotStateList<Member>, Any>(
        save = { rows -> rows.flatMap { listOf(it.id, it.name) } },
        restore = { flat ->
          mutableStateListOf<Member>().apply {
            for (i in flat.indices step 2) {
              add(
                  Member(
                      id = flat[i] as Long,
                      groupId = 0L,
                      name = flat[i + 1] as String,
                      createdAt = 0L))
            }
          }
        })

/** Ids of members the user removed, which the form has to remember but not display. */
private val longListSaver =
    listSaver<SnapshotStateList<Long>, Any>(
        save = { ids -> ids.toList() },
        restore = { flat -> mutableStateListOf<Long>().apply { addAll(flat.map { it as Long }) } })

@Composable
fun GroupEditorScreen(
    gid: Long?,
    done: () -> Unit,
    defaultCurrency: String = "USD",
    vm: GroupEditVm = hiltViewModel()
) {
  val g by remember(gid) { vm.group(gid ?: -1L) }.collectAsStateWithLifecycle(null)
  val ms by remember(gid) { vm.members(gid ?: -1L) }.collectAsStateWithLifecycle(emptyList())
  var name by rememberSaveable { mutableStateOf("") }
  var cur by rememberSaveable(defaultCurrency) { mutableStateOf(defaultCurrency) }
  // The self identity is a member id, and a member id is 0 until the row is in the
  // database. 0 is also how this screen spells "Nobody", so a row typed here cannot be
  // picked until it has an id of its own -- `nextId` hands it a negative one, which no
  // database row can ever hold. A name would not do: two members of a group may share one.
  var self by rememberSaveable { mutableStateOf(0L) }
  // Hands out the negative ids that mark a row as not yet saved. Saveable, because a
  // rotation that restarted it would reissue an id a row still holds and put two rows on
  // one identity.
  var nextId by rememberSaveable { mutableStateOf(-1L) }
  var color by rememberSaveable { mutableIntStateOf(0) }
  var loaded by rememberSaveable { mutableStateOf(false) }
  val rows = rememberSaveable(saver = memberListSaver) { mutableStateListOf<Member>() }
  val removed = rememberSaveable(saver = longListSaver) { mutableStateListOf<Long>() }
  var nm by rememberSaveable { mutableStateOf("") }
  var confirm by remember { mutableStateOf(false) }
  var stuckMsg by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(g, ms) {
    if (!loaded && (gid == null || g != null)) {
      val grp = g
      if (grp != null) {
        name = grp.name
        cur = grp.currencyCode
        self = grp.selfMemberId ?: 0L
        color = grp.color
      }
      rows.clear()
      rows.addAll(ms)
      loaded = true
    }
  }
  val canSave = name.isNotBlank() && (gid != null || cur.length == 3) && rows.isNotEmpty()
  val doSave: () -> Unit = {
    vm.save(
        name,
        cur,
        self,
        rows.toList(),
        gid ?: 0L,
        g?.currencyCode ?: "",
        color,
        removed.toList()) { stuck ->
          if (stuck.isEmpty()) done() else stuckMsg = "${stuck.joinToString(", ")} could not be removed"
        }
  }
  ChildShell(
      title = if (gid == null) "New group" else "Edit group",
      onBack = done,
      actions = { TextButton(onClick = doSave, enabled = canSave) { Text("Save") } },
  ) { p ->
    Column(
        Modifier.fillMaxSize().padding(p).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
          stuckMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          }
          OutlinedTextField(
              name,
              { name = it },
              label = { Text("Name") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth())
          if (gid == null)
              OutlinedTextField(
                  cur,
                  { cur = it.uppercase().take(3) },
                  label = { Text("Currency (ISO)") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth())
          Text("Color", style = MaterialTheme.typography.labelLarge)
          ColorDots(color) { color = it }
          Text("Members", style = MaterialTheme.typography.labelLarge)
          LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(
                rows,
                key = { m -> m.id }) { m ->
              ListItem(
                  headlineContent = { Text(m.name) },
                  trailingContent = {
                    TextButton(
                        onClick = {
                          rows.remove(m)
                          if (m.id > 0L) removed.add(m.id)
                        }) {
                          Text("Remove")
                        }
                  })
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                nm,
                { nm = it },
                label = { Text("Member name") },
                singleLine = true,
                modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                  if (nm.isNotBlank()) {
                    val newId = nextId
                    nextId--
                    rows.add(
                        Member(
                            id = newId,
                            groupId = gid ?: -1L,
                            name = nm.trim(),
                            createdAt = System.currentTimeMillis()))
                    nm = ""
                  }
                },
                enabled = nm.isNotBlank()) {
                  Text("Add")
                }
          }
          if (rows.isNotEmpty()) {
            Text("Highlight balances for", style = MaterialTheme.typography.labelLarge)
            Column {
              Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.fillMaxWidth().clickable { self = 0L }) {
                RadioButton(self == 0L, { self = 0L })
                Text("Nobody", modifier = Modifier.padding(start = 8.dp))
              }
              rows.forEach { m ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { self = m.id }) {
                  RadioButton(self == m.id, { self = m.id })
                  Text(m.name, modifier = Modifier.padding(start = 8.dp))
                }
              }
            }
          }
          if (gid != null) {
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
              Text("Delete group")
            }
            if (confirm)
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Delete group?") },
                    text = { Text("Removes the group and all its expenses.") },
                    confirmButton = {
                      TextButton(onClick = { vm.deleteGroup(gid) { done() } }) { Text("Delete") }
                    },
                    dismissButton = {
                      TextButton(onClick = { confirm = false }) { Text("Cancel") }
                    })
          }
        }
  }
}

