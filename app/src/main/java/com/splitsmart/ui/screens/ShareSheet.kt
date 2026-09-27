package com.splitsmart.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.splitsmart.core.settle.Transfer
import com.splitsmart.ui.vm.PAYPAL_LANDING
import com.splitsmart.ui.vm.paybackLine
import com.splitsmart.ui.vm.settleShareText
import com.splitsmart.ui.vm.venmoUri

private fun copyText(ctx: Context, text: String) {
  (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
      ClipData.newPlainText("settle-up", text))
  Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun openLink(ctx: Context, uri: String) {
  val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
  if (i.resolveActivity(ctx.packageManager) != null) runCatching { ctx.startActivity(i) }
  else Toast.makeText(ctx, "No app found for this link", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettleShareSheet(
    groupName: String,
    plan: List<Transfer>,
    names: Map<Long, String>,
    summary: String? = null,
    onDismiss: () -> Unit
) {
  val ctx = LocalContext.current
  var sel by remember(plan) { mutableStateOf(0) }
  val t = plan.getOrNull(sel)
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Share settle-up", style = MaterialTheme.typography.titleLarge)
          LazyColumn(Modifier.heightIn(max = 220.dp)) {
            itemsIndexed(plan, key = { _, x -> "${x.from}-${x.to}-${x.currencyCode}" }) { i, x ->
              Row(
                  Modifier.fillMaxWidth().clickable { sel = i }.padding(8.dp),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(i == sel, { sel = i })
                    Text(
                        paybackLine(names[x.from] ?: "?", names[x.to] ?: "?", x.amountMinor, x.currencyCode),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp))
                  }
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { copyText(ctx, settleShareText(groupName, plan, names)) },
                modifier = Modifier.weight(1f)) {
                  Text("Copy all")
                }
            OutlinedButton(
                onClick = {
                  runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                          type = "text/plain"
                          putExtra(Intent.EXTRA_TEXT, settleShareText(groupName, plan, names))
                        })
                  }
                },
                modifier = Modifier.weight(1f)) {
                  Text("Share")
                }
          }
          if (summary != null) {
            OutlinedButton(
                onClick = { copyText(ctx, summary) }, modifier = Modifier.fillMaxWidth()) {
                  Text("Copy full summary")
                }
          }
          t?.let { x ->
            val to = names[x.to] ?: "?"
            val note = "$groupName settle-up"
            val venmoSafe = x.currencyCode.equals("USD", true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(
                  onClick = { openLink(ctx, venmoUri(to, x.amountMinor, note)) },
                  enabled = venmoSafe,
                  modifier = Modifier.weight(1f)) {
                    Text("Venmo")
                  }
              OutlinedButton(
                  onClick = { openLink(ctx, PAYPAL_LANDING) }, modifier = Modifier.weight(1f)) {
                    Text("PayPal")
                  }
            }
            Text(
                if (venmoSafe)
                    "Links open the payment app with amount filled in; pick the recipient there."
                else
                    "Venmo bills in US dollars, so its button is off for this ${x.currencyCode} payment. Copy or share the line instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
  }
}
