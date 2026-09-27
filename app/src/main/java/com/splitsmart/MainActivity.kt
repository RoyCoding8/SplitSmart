package com.splitsmart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import com.splitsmart.data.ThemeMode
import com.splitsmart.ui.nav.*
import com.splitsmart.ui.screens.*
import com.splitsmart.ui.theme.SplitSmartTheme
import com.splitsmart.ui.vm.SettingsVm
import com.splitsmart.widget.ShortcutActions
import com.splitsmart.widget.ShortcutHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val startAction =
        when (intent?.action) {
          ShortcutActions.ADD_GROUP -> "wizard"
          ShortcutActions.OPEN_SEARCH -> "search"
          // A shortcut with no group falls back to the last one opened, which is what a
          // static "New expense" is asking for. The extra is preferred, because the
          // dynamic shortcut that names a group can point somewhere the last-opened group
          // is not. Read only when the extra is missing, so an icon launch reads nothing.
          ShortcutActions.ADD_EXPENSE -> {
            val named = intent.getLongExtra(ShortcutActions.EXTRA_GID, -1L)
            val gid =
                if (named > 0L) named
                else runBlocking { ShortcutHelper.lastGroupId(this@MainActivity) }
            "expense:$gid"
          }
          else -> null
        }
    // Consumed here, not when the destination is reached. Left on the intent, every later
    // onCreate replays the navigation.
    intent?.action = null
    setContent {
      val svm: SettingsVm = hiltViewModel()
      val prefs by svm.settings.collectAsStateWithLifecycle()
      val dark =
          when (prefs.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
          }
      SplitSmartTheme(dark = dark, dynamic = prefs.dynamicColor) {
        SplitSmartNav(prefs.defaultCurrency, startAction)
      }
    }
  }
}

@Composable
fun SplitSmartNav(defaultCurrency: String = "USD", startAction: String? = null) {
  val nav = rememberNavController()
  LaunchedEffect(startAction) {
    when {
      startAction == "wizard" -> nav.navigate(AddEditGroup())
      startAction == "search" -> nav.navigate(Search)
      startAction?.startsWith("expense:") == true -> {
        val gid = startAction.removePrefix("expense:").toLongOrNull() ?: -1L
        // With no group to bill, the new-GROUP screen is the destination. An expense
        // editor bound to a group that does not exist cannot save, so it is a dead screen
        // with an enabled-looking amount field and nothing to say why.
        if (gid > 0) nav.navigate(AddEditExpense(gid)) else nav.navigate(AddEditGroup())
      }
    }
  }
  NavHost(nav, startDestination = Home) {
    composable<Home> {
      HomeScreen(
          { nav.navigate(GroupDetail(it)) },
          { nav.navigate(AddEditGroup()) },
          { nav.navigate(Settings) },
          {
            nav.navigate(Stats) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
          defaultCurrency,
          { nav.navigate(FriendDetail(it)) },
          {
            nav.navigate(Search) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
      )
    }
    composable<Search> {
      SearchScreen(
          { nav.navigate(GroupDetail(it)) },
          { g, eid -> nav.navigate(ExpenseDetail(g, eid)) },
          {
            nav.navigate(Home) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
          {
            nav.navigate(Stats) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
          { nav.navigate(Settings) },
      )
    }
    composable<FriendDetail> { r ->
      val gid = r.toRoute<FriendDetail>().groupId
      FriendDetailScreen(
          gid,
          { nav.navigate(AddEditExpense(it)) },
          { nav.navigate(AddEditGroup(it)) },
          { nav.popBackStack() },
          { g, eid -> nav.navigate(ExpenseDetail(g, eid)) },
      )
    }
    composable<Stats> {
      StatsScreen(
          { nav.navigate(GroupDetail(it)) },
          {
            nav.navigate(Home) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
          { nav.navigate(Settings) },
          {
            nav.navigate(Search) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
      )
    }
    composable<GroupDetail> { r ->
      val gid = r.toRoute<GroupDetail>().groupId
      GroupDetailScreen(
          gid = gid,
          addExpense = { nav.navigate(AddEditExpense(it)) },
          editExpense = { g, eid -> nav.navigate(AddEditExpense(g, eid)) },
          charts = { nav.navigate(Charts(it)) },
          recurring = { nav.navigate(Recurring(it)) },
          editGroup = { nav.navigate(AddEditGroup(it)) },
          history = { nav.navigate(History(it)) },
          settings = { nav.navigate(Settings) },
          onBack = { nav.popBackStack() },
          openExpense = { g, eid -> nav.navigate(ExpenseDetail(g, eid)) },
      )
    }
    composable<ExpenseDetail> { r ->
      val a = r.toRoute<ExpenseDetail>()
      ExpenseDetailScreen(
          a.groupId,
          a.expenseId,
          { g, eid -> nav.navigate(AddEditExpense(g, eid)) },
          { nav.popBackStack() },
      )
    }
    composable<AddEditExpense> { r ->
      val a = r.toRoute<AddEditExpense>()
      ExpenseEditorScreen(a.groupId, a.expenseId) { nav.popBackStack() }
    }
    composable<History> { r ->
      HistoryScreen(r.toRoute<History>().groupId, onBack = { nav.popBackStack() })
    }
    composable<Charts> { r ->
      ChartsScreen(r.toRoute<Charts>().groupId, onBack = { nav.popBackStack() })
    }
    composable<Recurring> { r ->
      RecurringScreen(r.toRoute<Recurring>().groupId, onBack = { nav.popBackStack() })
    }
    composable<Settings> {
      SettingsScreen(
          {
            nav.navigate(Home) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
          {
            nav.navigate(Stats) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
          {
            nav.navigate(Search) {
              popUpTo(Home) { inclusive = false }
              launchSingleTop = true
            }
          },
      )
    }
    composable<AddEditGroup> { r ->
      GroupEditorScreen(r.toRoute<AddEditGroup>().groupId, { nav.popBackStack() }, defaultCurrency)
    }
  }
}
