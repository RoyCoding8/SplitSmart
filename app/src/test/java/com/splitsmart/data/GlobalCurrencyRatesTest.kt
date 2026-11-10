package com.splitsmart.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalCurrencyRatesTest : DbTest() {
  @Test
  fun `shared rates apply to groups and friends and local rates override them`() = runTest {
    val file = File.createTempFile("splitsmart-fx-", ".preferences_pb")
    file.delete() // DataStore must create the file itself.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val settings =
          SettingsPrefsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
      val withSharedRates = SplitRepository(db, settings)
      val group = withSharedRates.saveGroup(
          Group(name = "Trip", currencyCode = "USD", createdAt = now))
      val friend = withSharedRates.friendGroup("Sam", "USD")

      settings.saveGlobalRate("EUR", "USD", 1.2)
      assertThat(pickRate(withSharedRates.rates(group).first(), "EUR", "USD", now))
          .isEqualTo(1.2)
      assertThat(pickRate(withSharedRates.rates(friend).first(), "EUR", "USD", now))
          .isEqualTo(1.2)

      withSharedRates.saveRate(
          FxRate(groupId = group, fromCode = "USD", toCode = "EUR",
              rate = 0.5, timeEpoch = now))
      assertThat(pickRate(withSharedRates.rates(group).first(), "EUR", "USD", now))
          .isEqualTo(2.0)
      assertThat(pickRate(withSharedRates.rates(friend).first(), "EUR", "USD", now))
          .isEqualTo(1.2)

      settings.saveGlobalRate("USD", "EUR", 0.8)
      assertThat(settings.flow.first().globalRates).hasSize(1)
      assertThat(pickRate(withSharedRates.rates(friend).first(), "EUR", "USD", now))
          .isEqualTo(1.25)
      settings.deleteGlobalRate("USD", "EUR")
      assertThat(settings.flow.first().globalRates).isEmpty()
      assertThat(unconvertibleCurrencies(
          listOf(Expense(groupId = friend, payerId = 1, amountMinor = 100,
              currencyCode = "EUR", category = Category.FOOD,
              dateEpoch = now, createdAt = now)),
          "USD", withSharedRates.rates(friend).first())).containsExactly("EUR")
    } finally {
      scope.cancel()
      file.delete()
    }
  }
}
