package com.splitsmart.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitsmart.data.CustomCategory
import com.splitsmart.data.Exporter
import com.splitsmart.data.Group
import com.splitsmart.data.SettingsPrefs
import com.splitsmart.data.SettingsPrefsStore
import com.splitsmart.data.SplitRepository
import com.splitsmart.data.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsVm
@Inject
constructor(
    private val prefs: SettingsPrefsStore,
    private val repo: SplitRepository,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {
  val settings: StateFlow<SettingsPrefs> =
      prefs.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsPrefs())
  val groups: StateFlow<List<Group>> =
      repo.allGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  val customs: StateFlow<List<CustomCategory>> =
      repo.customCats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  suspend fun saveCustomCat(c: CustomCategory): String? =
      runCatching {
            repo.saveCustomCat(c)
            null
          }
          .getOrElse { it.message }

  suspend fun deleteCustomCat(id: Long): String? =
      runCatching {
            repo.deleteCustomCat(id)
            null
          }
          .getOrElse { it.message }

  fun setThemeMode(m: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(m) }

  fun setDynamicColor(b: Boolean) = viewModelScope.launch { prefs.setDynamicColor(b) }

  /** Returns the failure message, or null on success, like the custom-category writes. */
  fun setDefaultCurrency(code: String, onResult: (String?) -> Unit = {}) =
      viewModelScope.launch {
        val err =
            runCatching { prefs.setDefaultCurrency(code) }
                .exceptionOrNull()
                ?.message
        onResult(err)
      }

  fun setBillNotifs(b: Boolean) = viewModelScope.launch { prefs.setBillNotifs(b) }

  private fun exporter() = Exporter(repo, ctx.contentResolver, ctx.filesDir)

  suspend fun backupJson(): String = exporter().backup()

  suspend fun restoreJson(json: String): Result<Exporter.RestoreResult> =
      runCatching { exporter().restore(json) }

  suspend fun groupCsv(gid: Long): String = exporter().csv(exporter().dumpGroup(gid))

  suspend fun importCsv(
      name: String,
      currency: String,
      csv: String
  ): Result<Exporter.ImportResult> = runCatching { exporter().importCsv(name, currency, csv) }
}
