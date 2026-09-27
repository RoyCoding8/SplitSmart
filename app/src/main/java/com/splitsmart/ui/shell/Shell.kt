package com.splitsmart.ui.shell

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class DrawerDest {
  Groups,
  Search,
  Stats,
  Settings
}

@Composable
fun appVersion(): String? {
  val ctx = LocalContext.current
  return runCatching {
        val pm = ctx.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              pm.getPackageInfo(
                  ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
              @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, 0)
            }
        info.versionName
      }
      .getOrNull()
}

@Composable
private fun DrawerItems(selected: DrawerDest, onOpen: (DrawerDest) -> Unit) {
  NavigationDrawerItem(
      icon = { Icon(Icons.Default.Group, null) },
      label = { Text("Groups") },
      selected = selected == DrawerDest.Groups,
      onClick = { onOpen(DrawerDest.Groups) },
      modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
  )
  NavigationDrawerItem(
      icon = { Icon(Icons.Default.Search, null) },
      label = { Text("Search") },
      selected = selected == DrawerDest.Search,
      onClick = { onOpen(DrawerDest.Search) },
      modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
  )
  NavigationDrawerItem(
      icon = { Icon(Icons.Default.BarChart, null) },
      label = { Text("Stats") },
      selected = selected == DrawerDest.Stats,
      onClick = { onOpen(DrawerDest.Stats) },
      modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
  )
  NavigationDrawerItem(
      icon = { Icon(Icons.Default.Settings, null) },
      label = { Text("Settings") },
      selected = selected == DrawerDest.Settings,
      onClick = { onOpen(DrawerDest.Settings) },
      modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellBar(
    title: String,
    centered: Boolean = false,
    navIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
  if (centered) CenterAlignedTopAppBar({ Text(title) }, navigationIcon = navIcon, actions = actions)
  else TopAppBar({ Text(title) }, navigationIcon = navIcon, actions = actions)
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TopLevelShell(
    title: String,
    selected: DrawerDest,
    centered: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    onOpenGroups: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
  val drawer = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val version = appVersion()
  val ctx = LocalContext.current
  val wide =
      (ctx as? Activity)?.let {
        val w = calculateWindowSizeClass(it).widthSizeClass
        w == WindowWidthSizeClass.Medium || w == WindowWidthSizeClass.Expanded
      } == true
  val open: (DrawerDest) -> Unit = { d ->
    scope.launch { drawer.close() }
    if (d != selected) {
      when (d) {
        DrawerDest.Groups -> onOpenGroups()
        DrawerDest.Search -> onOpenSearch()
        DrawerDest.Stats -> onOpenStats()
        DrawerDest.Settings -> onOpenSettings()
      }
    }
  }
  if (wide) {
    Row(Modifier.fillMaxSize()) {
      NavigationRail {
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected == DrawerDest.Groups,
            { open(DrawerDest.Groups) },
            icon = { Icon(Icons.Default.Group, "Groups") },
            label = { Text("Groups") })
        NavigationRailItem(
            selected == DrawerDest.Search,
            { open(DrawerDest.Search) },
            icon = { Icon(Icons.Default.Search, "Search") },
            label = { Text("Search") })
        NavigationRailItem(
            selected == DrawerDest.Stats,
            { open(DrawerDest.Stats) },
            icon = { Icon(Icons.Default.BarChart, "Stats") },
            label = { Text("Stats") })
        NavigationRailItem(
            selected == DrawerDest.Settings,
            { open(DrawerDest.Settings) },
            icon = { Icon(Icons.Default.Settings, "Settings") },
            label = { Text("Settings") })
        Spacer(Modifier.weight(1f))
      }
      Scaffold(
          modifier = Modifier.weight(1f),
          topBar = { ShellBar(title, centered, actions = actions) }) { p ->
            content(p)
          }
    }
  } else {
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
          ModalDrawerSheet {
            Text(
                "SplitSmart",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(28.dp, 24.dp, 28.dp, 8.dp))
            DrawerItems(selected, open)
            Spacer(Modifier.weight(1f))
            version?.let {
              Text(
                  "v$it",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(28.dp, 8.dp, 28.dp, 16.dp))
            }
          }
        },
    ) {
      Scaffold(
          topBar = {
            ShellBar(
                title,
                centered,
                navIcon = {
                  IconButton({ scope.launch { drawer.open() } }) {
                    Icon(Icons.Default.Menu, "Open navigation")
                  }
                },
                actions = actions)
          },
      ) { p ->
        content(p)
      }
    }
  }
}

@Composable
fun ChildShell(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
  Scaffold(
      topBar = {
        ShellBar(
            title,
            navIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = actions)
      },
  ) { p ->
    content(p)
  }
}
