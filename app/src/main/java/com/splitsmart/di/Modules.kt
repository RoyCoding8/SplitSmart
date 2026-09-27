package com.splitsmart.di

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.splitsmart.data.AppDatabase
import com.splitsmart.data.GroupDefaultsStore
import com.splitsmart.data.MIGRATION_1_2
import com.splitsmart.data.MIGRATION_2_3
import com.splitsmart.data.MIGRATION_3_4
import com.splitsmart.data.MIGRATION_4_5
import com.splitsmart.data.MIGRATION_5_6
import com.splitsmart.data.MIGRATION_6_7
import com.splitsmart.data.MIGRATION_7_8
import com.splitsmart.data.MIGRATION_8_9
import com.splitsmart.data.MIGRATION_9_10
import com.splitsmart.data.MIGRATION_10_11
import com.splitsmart.data.MIGRATION_11_12
import com.splitsmart.data.SettingsPrefsStore
import com.splitsmart.data.SplitRepository
import com.splitsmart.widget.BalanceWidget
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.Closeable
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

val Context.store by preferencesDataStore("settings")

@Module
@InstallIn(SingletonComponent::class)
object Modules {
  @Provides
  @Singleton
  fun db(@ApplicationContext c: Context): AppDatabase =
      Room.databaseBuilder(c, AppDatabase::class.java, "splitsmart.db")
          .addMigrations(
              MIGRATION_1_2,
              MIGRATION_2_3,
              MIGRATION_3_4,
              MIGRATION_4_5,
              MIGRATION_5_6,
              MIGRATION_6_7,
              MIGRATION_7_8,
              MIGRATION_8_9,
              MIGRATION_9_10,
              MIGRATION_10_11,
              MIGRATION_11_12)
          .build()

  @Provides @Singleton fun repo(db: AppDatabase) = SplitRepository(db)

  /**
   * The balance widget has no update period, so it would otherwise show whatever it
   * rendered when it was placed. Room tells us when the tables it reads have
   * changed, which covers every write path without a refresh call at each one.
   *
   * The table list is the observer's constructor filter, so Room drops the writes we
   * do not care about before `onInvalidated` is ever called, and it rejects a typo
   * here at construction instead of silently never firing.
   *
   * Room also fires the tracker in bursts -- one write transaction invalidates every
   * table it touched -- so the refresh is conflated: a pending job is replaced rather
   * than run alongside. Without that, a bulk restore would push one widget update
   * per table per row.
   */
  @Provides
  @Singleton
  fun watchWidget(
      db: AppDatabase,
      @ApplicationContext c: Context
  ): Closeable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var pending: Job? = null
    // fx_rates is in the list because the widget reports balances converted into
    // the group currency, so a rate that is added or corrected changes what it
    // should show while touching no table the widget was watching. Without it the
    // widget kept displaying balances at the old rate until something else in the
    // group happened to change.
    val observer =
        object :
            InvalidationTracker.Observer(
                "groups", "members", "expenses", "shares", "payments", "settlements", "fx_rates") {
          override fun onInvalidated(tables: Set<String>) {
            pending?.cancel()
            pending = scope.launch { BalanceWidget().refreshAll(c) }
          }
        }
    db.invalidationTracker.addObserver(observer)
    return Closeable {
      db.invalidationTracker.removeObserver(observer)
      scope.cancel()
    }
  }

  @Provides @Singleton fun defaults(@ApplicationContext c: Context) = GroupDefaultsStore(c.store)

  @Provides
  @Singleton
  fun settingsPrefs(@ApplicationContext c: Context) = SettingsPrefsStore(c.store)
}
