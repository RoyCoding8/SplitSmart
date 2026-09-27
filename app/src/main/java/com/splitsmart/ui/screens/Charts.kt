package com.splitsmart.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitsmart.data.PerCurrency
import com.splitsmart.data.monthlyTotals
import com.splitsmart.data.totalsByCatLabel
import com.splitsmart.data.totalsByPayer
import com.splitsmart.ui.shell.ChildShell
import com.splitsmart.ui.vm.GroupDetailVm
import com.splitsmart.ui.vm.money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChartsScreen(gid: Long, onBack: () -> Unit = {}, vm: GroupDetailVm = hiltViewModel()) {
  LaunchedEffect(gid) { vm.bind(gid) }
  val expenses by vm.expenses.collectAsStateWithLifecycle()
  val members by vm.members.collectAsStateWithLifecycle()
  val g by vm.group.collectAsStateWithLifecycle()
  val cur = g?.currencyCode ?: ""
  val names = remember(members) { members.associate { it.id to it.name } }
  val customs by vm.customs.collectAsStateWithLifecycle()
  val customMap = remember(customs) { customs.associateBy { it.id } }
  val byCat = remember(expenses, customMap) { totalsByCatLabel(expenses, customMap) }
  val byPayer = remember(expenses, names) { totalsByPayer(expenses).map { (id, amt) -> (names[id] ?: "?") to amt } }
  val monthly = remember(expenses) { monthlyTotals(expenses) }
  val fmt = DateTimeFormatter.ofPattern("MMM yy").withZone(ZoneId.systemDefault())
  ChildShell(title = "Charts", onBack = onBack) { p ->
    LazyColumn(
        Modifier.fillMaxSize().padding(p).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
          if (expenses.isEmpty()) item { Text("No expenses yet. Charts will show up here.") }
          else {
            item { Text("By category", style = MaterialTheme.typography.titleMedium) }
            item { BarChart(byCat) }
            item { Text("By payer", style = MaterialTheme.typography.titleMedium) }
            item { BarChart(byPayer) }
            if (monthly.size > 1) {
              item { Text("Monthly trend", style = MaterialTheme.typography.titleMedium) }
              item {
                BarChart(monthly.map { fmt.format(Instant.ofEpochMilli(it.first)) to it.second })
              }
            }
          }
        }
  }
}

/**
 * One bar per (label, currency) pair.
 *
 * A label holding two currencies becomes two bars. Summing them produces a number that is
 * wrong rather than approximate, since a bar has no exchange rate attached to it and the
 * group currency is only right by coincidence.
 */
@Composable
private fun BarChart(rows: List<Pair<String, PerCurrency>>) {
  if (rows.isEmpty()) {
    Text("Nothing to show.")
    return
  }
  val flat =
      rows.flatMap { (label, cur) -> cur.map { (code, amt) -> Triple("$label · $code", amt, code) } }
  if (flat.isEmpty()) {
    Text("Nothing to show.")
    return
  }
  val max = flat.maxOf { it.second }.coerceAtLeast(1L)
  val bar = MaterialTheme.colorScheme.primary
  val ink = MaterialTheme.colorScheme.onSurface
  val measurer = rememberTextMeasurer()
  val style = MaterialTheme.typography.bodySmall.copy(color = ink)
  val rowH = 30.dp
  Canvas(Modifier.fillMaxWidth().height(rowH * flat.size)) {
    flat.forEachIndexed { i, (label, amt, code) ->
      val y = i * rowH.toPx()
      val labelText = measurer.measure(label, style)
      drawText(labelText, topLeft = androidx.compose.ui.geometry.Offset(0f, y + 4f))
      val barX = labelText.size.width + 8.dp.toPx()
      val frac = amt.toFloat() / max
      drawRoundRect(
          bar,
          topLeft = androidx.compose.ui.geometry.Offset(barX, y + 2f),
          size = Size((size.width - barX - 64.dp.toPx()).coerceAtLeast(0f) * frac, rowH.toPx() - 8f),
          cornerRadius = CornerRadius(4.dp.toPx()))
      drawText(
          measurer,
          money(amt, code),
          topLeft =
              androidx.compose.ui.geometry.Offset(
                  (barX + (size.width - barX - 64.dp.toPx()) * frac + 4.dp.toPx()).coerceAtMost(
                      size.width - 60.dp.toPx()),
                  y + 4f),
          style = style)
    }
  }
}
