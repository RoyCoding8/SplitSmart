package com.splitsmart

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.splitsmart.work.RecurringWorker
import dagger.hilt.android.HiltAndroidApp
import java.io.Closeable
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SplitSmartApp : Application() {
  /** Held so the provider is actually instantiated; the observer registers itself. */
  @Inject lateinit var widgetWatch: Closeable

  override fun onCreate() {
    super.onCreate()
    // Swallowed on purpose: without a WorkManager-initializer provider (a
    // secondary process, a stripped test harness) this app still has to start, it
    // just has no recurring bills.
    try {
      WorkManager.getInstance(this)
          .enqueueUniquePeriodicWork(
              "recurring",
              ExistingPeriodicWorkPolicy.KEEP,
              PeriodicWorkRequestBuilder<RecurringWorker>(12, TimeUnit.HOURS).build(),
          )
    } catch (_: IllegalStateException) {}
  }
}
