package com.splitsmart.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK
}

/** A manual conversion rate shared by every friend and group. */
@Serializable
data class AppFxRate(val fromCode: String, val toCode: String, val rate: Double, val timeEpoch: Long)

data class SettingsPrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultCurrency: String = "USD",
    val billNotifs: Boolean = true,
    val globalRates: List<AppFxRate> = emptyList(),
)

class SettingsPrefsStore(private val store: DataStore<Preferences>) {
  private val themeKey = stringPreferencesKey("ui_theme")
  private val dynamicKey = booleanPreferencesKey("ui_dynamic")
  private val currencyKey = stringPreferencesKey("default_currency")
  private val notifKey = booleanPreferencesKey("bill_notifs")
  private val globalRatesKey = stringPreferencesKey("global_fx_rates_v1")
  private val lastGroupKey = longPreferencesKey("last_group_id")

  val lastGroupId: Flow<Long> = store.data.map { it[lastGroupKey] ?: -1L }.catch { emit(-1L) }

  val flow: Flow<SettingsPrefs> =
      store.data
          .map { p ->
            SettingsPrefs(
                themeMode =
                    runCatching { ThemeMode.valueOf(p[themeKey] ?: "SYSTEM") }
                        .getOrDefault(ThemeMode.SYSTEM),
                dynamicColor = p[dynamicKey] ?: true,
                defaultCurrency =
                    (p[currencyKey] ?: "USD").uppercase().takeIf { it.length == 3 } ?: "USD",
                billNotifs = p[notifKey] ?: true,
                globalRates = readGlobalRates(p[globalRatesKey]),
            )
          }
          .catch { emit(SettingsPrefs()) }

  suspend fun setThemeMode(m: ThemeMode) {
    store.edit { it[themeKey] = m.name }
  }

  suspend fun setDynamicColor(b: Boolean) {
    store.edit { it[dynamicKey] = b }
  }

  suspend fun setDefaultCurrency(code: String) {
    val v = code.trim().uppercase().take(3)
    require(v.length == 3) { "currency must be 3 letters" }
    store.edit { it[currencyKey] = v }
  }

  private fun readGlobalRates(raw: String?): List<AppFxRate> =
      runCatching { Json.decodeFromString<List<AppFxRate>>(raw ?: "[]") }
          .getOrDefault(emptyList())
          .filter { r ->
            r.fromCode.length == 3 && r.toCode.length == 3 &&
                r.fromCode != r.toCode && r.rate.isFinite() && r.rate > 0
          }

  /** Replaces a currency pair's shared rate; never changes any group-specific rates. */
  suspend fun saveGlobalRate(from: String, to: String, rate: Double) {
    val f = from.trim().uppercase()
    val t = to.trim().uppercase()
    require(f.length == 3 && t.length == 3 && f != t && f.all(Char::isLetter) &&
        t.all(Char::isLetter)) { "Enter two different 3-letter currency codes." }
    require(rate.isFinite() && rate > 0) { "Enter a positive rate." }
    store.edit { p ->
      val existing = readGlobalRates(p[globalRatesKey])
      val newRate = AppFxRate(f, t, rate, System.currentTimeMillis())
      // An inverse quote is for the same pair: keep only the new quote so conversion
      // never depends on which direction was entered first.
      p[globalRatesKey] =
          Json.encodeToString(
              existing.filterNot {
                (it.fromCode == f && it.toCode == t) ||
                    (it.fromCode == t && it.toCode == f)
              } + newRate)
    }
  }

  suspend fun deleteGlobalRate(from: String, to: String) {
    store.edit { p ->
      p[globalRatesKey] =
          Json.encodeToString(
              readGlobalRates(p[globalRatesKey])
                  .filterNot { it.fromCode == from && it.toCode == to })
    }
  }

  suspend fun setBillNotifs(b: Boolean) {
    store.edit { it[notifKey] = b }
  }

  suspend fun setLastGroup(id: Long) {
    store.edit { it[lastGroupKey] = id }
  }

  /**
   * Drops the remembered group if it is the one named, so the id can never outlive the group it
   * points at. A stale id opens an editor bound to a group that no longer exists: no members to
   * split between and nothing on screen to say why.
   *
   * The rule lives here, on the class that owns the pref, because a group can be deleted through
   * two different doors. A pref failure is swallowed on purpose: this runs after the group row is
   * already gone, so there is nothing left to abort and nobody left to tell.
   */
  suspend fun forgetGroup(id: Long) {
    runCatching {
      store.edit { if (it[lastGroupKey] == id) it.remove(lastGroupKey) }
    }
  }
}
