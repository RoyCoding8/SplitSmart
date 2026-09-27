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

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK
}

data class SettingsPrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultCurrency: String = "USD",
    val billNotifs: Boolean = true,
)

class SettingsPrefsStore(private val store: DataStore<Preferences>) {
  private val themeKey = stringPreferencesKey("ui_theme")
  private val dynamicKey = booleanPreferencesKey("ui_dynamic")
  private val currencyKey = stringPreferencesKey("default_currency")
  private val notifKey = booleanPreferencesKey("bill_notifs")
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
