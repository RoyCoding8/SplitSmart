package com.splitsmart.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitsmart.MainActivity
import com.splitsmart.data.SettingsPrefsStore
import com.splitsmart.data.SplitRepository
import com.splitsmart.ui.vm.money
import com.splitsmart.data.unpricedNote
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

const val BILLS_CHANNEL = "bills"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecurringEntryPoint {
  fun repo(): SplitRepository

  fun prefs(): SettingsPrefsStore
}

fun postSummary(ctx: Context, lines: List<String>) {
  val nm = ctx.getSystemService(NotificationManager::class.java)
  nm.createNotificationChannel(
      NotificationChannel(BILLS_CHANNEL, "Bills", NotificationManager.IMPORTANCE_DEFAULT))
  val open =
      PendingIntent.getActivity(
          ctx,
          0,
          Intent(ctx, MainActivity::class.java),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  nm.notify(
      1,
      NotificationCompat.Builder(ctx, BILLS_CHANNEL)
          .setSmallIcon(android.R.drawable.ic_dialog_info)
          .setContentTitle("SplitSmart")
          .setContentText(lines.first())
          .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
          .setContentIntent(open)
          .setAutoCancel(true)
          .build(),
  )
}

class RecurringWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val entry = EntryPoints.get(applicationContext, RecurringEntryPoint::class.java)
    val made = entry.repo().generateAllDue()
    val nudges = entry.repo().nudgedOutstanding()
    if ((made == 0 && nudges.isEmpty()) || !entry.prefs().flow.first().billNotifs)
        return Result.success()
    if (Build.VERSION.SDK_INT >= 33 &&
        ActivityCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED)
        return Result.success()
    postSummary(
        applicationContext,
        buildList {
          if (made > 0) add("$made recurring expense${if (made == 1) "" else "s"} generated")
          // Same rule the widget and the screens apply: withhold the amount, name
          // the missing rate. A notification has no second number to compare
          // against and nothing to tap, so a parity figure is stated as fact.
          nudges.take(4).forEach { n ->
            add(
                if (n.unpriced.isNotEmpty())
                    "${n.memberName} ${if (n.netMinor > 0) "is owed" else "owes"} money in ${n.groupName}, but ${unpricedNote(n.unpriced)}"
                else if (n.netMinor > 0)
                    "${n.memberName} is owed ${money(n.netMinor, n.currencyCode)} in ${n.groupName}"
                else "${n.memberName} owes ${money(-n.netMinor, n.currencyCode)} in ${n.groupName}")
          }
        },
    )
    return Result.success()
  }
}
