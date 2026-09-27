package com.splitsmart.widget

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.splitsmart.MainActivity
import com.splitsmart.data.SplitRepository
import com.splitsmart.data.unconvertibleCurrencies
import com.splitsmart.data.unpricedNote
import com.splitsmart.ui.vm.DashRow
import com.splitsmart.ui.vm.minorToDecimal
import com.splitsmart.ui.vm.summarizeDashboard
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BalanceWidgetEntryPoint {
  fun repo(): SplitRepository
}

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}

class BalanceWidget : GlanceAppWidget() {
  override val sizeMode: SizeMode = SizeMode.Single

  /**
   * Glance has no `updateAll`, so refreshing means walking the placed instances'
   * ids. Each update is independent: one host rejecting the broadcast must not
   * strand the rest.
   */
  suspend fun refreshAll(context: Context) {
    val manager = GlanceAppWidgetManager(context)
    val ids = runCatching { manager.getGlanceIds(BalanceWidget::class.java) }.getOrNull() ?: return
    ids.forEach { runCatching { update(context, it) } }
  }

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    // A read that fails is not a group with nothing outstanding, and the widget
    // used to treat it as one: it fell back to an empty list, which renders as
    // "All settled up". So a database that could not be opened showed good news
    // about money owed. It says so instead.
    val state =
        runCatching { loadRows(context) }
            .fold(onSuccess = { WidgetState.Balances(it) }, onFailure = { WidgetState.Failed })
    provideContent { Body(state) }
  }

  /** What the widget knows: balances, or the fact that it could not read them. */
  private sealed interface WidgetState {
    data class Balances(val rows: List<DashRow>) : WidgetState

    data object Failed : WidgetState
  }

  @Composable
  private fun Body(state: WidgetState) {
    GlanceTheme(ColorProviders(lightColorScheme(), darkColorScheme())) {
      Column(
          modifier =
              GlanceModifier.fillMaxSize()
                  .background(GlanceTheme.colors.surface)
                  .padding(16.dp)
                  .clickable(actionStartActivity<MainActivity>()),
          verticalAlignment = Alignment.Top,
      ) {
        Text(
            "SplitSmart",
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
        )
        when (state) {
          is WidgetState.Failed ->
              Text("Could not read balances", style = TextStyle(color = GlanceTheme.colors.error))
          is WidgetState.Balances ->
              if (state.rows.isEmpty()) {
                Text("All settled up", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
              } else {
                state.rows.take(3).forEach { r ->
                  // Same rule the notification and the screens apply: the amount
                  // is withheld and the missing rate named. A widget is the least
                  // checkable surface there is, so a parity figure has nothing to
                  // be checked against.
                  val line =
                      if (r.unpriced.isNotEmpty()) {
                        "${r.currency}: ${unpricedNote(r.unpriced)}"
                      } else if (r.owe > 0 && r.owed > 0) {
                        "${r.currency}: owe ${minorToDecimal(r.owe)}, owed ${minorToDecimal(r.owed)}"
                      } else if (r.owe > 0) {
                        "${r.currency}: you owe ${minorToDecimal(r.owe)}"
                      } else {
                        "${r.currency}: owed ${minorToDecimal(r.owed)}"
                      }
                  Text(line, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                }
              }
        }
      }
    }
  }

  private suspend fun loadRows(ctx: Context): List<DashRow> {
    val repo = EntryPoints.get(ctx.applicationContext, BalanceWidgetEntryPoint::class.java).repo()
    // `groups()` already filters archived in SQL. A row is the sum of every group
    // behind it, so one group resting on parity leaves the whole row unpriced.
    val per =
        repo.groups().first().map { g ->
          Triple(
              g.currencyCode,
              g.selfMemberId?.let { repo.nets(g.id).first()[it] } ?: 0L,
              unconvertibleCurrencies(
                  repo.expenses(g.id).first(), g.currencyCode, repo.rates(g.id).first()))
        }
    return summarizeDashboard(
        per.map { it.first to it.second },
        per.filter { it.third.isNotEmpty() }
            .groupBy({ it.first }, { it.third })
            .mapValues { (_, v) -> v.flatten() })
  }
}
