package com.splitsmart.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LastGroupForgetTest {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()

  private val files = mutableListOf<File>()

  private fun store(name: String): SettingsPrefsStore {
    val f = File(ctx.filesDir, "lastgroup_$name.preferences_pb")
    files += f
    return SettingsPrefsStore(PreferenceDataStoreFactory.create { f })
  }

  @After
  fun clean() {
    files.forEach { runCatching { it.delete() } }
  }

  @Test
  fun `forgetting the remembered group clears the id`() = runTest {
    val s = store("self")
    s.setLastGroup(42)
    s.forgetGroup(42)
    assertThat(s.lastGroupId.first()).isEqualTo(-1L)
  }

  @Test
  fun `forgetting another group leaves the remembered id alone`() = runTest {
    val s = store("other")
    s.setLastGroup(42)
    s.forgetGroup(7)
    assertThat(s.lastGroupId.first()).isEqualTo(42L)
  }

  @Test
  fun `forgetting the remembered group twice is the same as once`() = runTest {
    val s = store("twice")
    s.setLastGroup(42)
    s.forgetGroup(42)
    s.forgetGroup(42)
    assertThat(s.lastGroupId.first()).isEqualTo(-1L)
  }

  @Test
  fun `forgetting a group when none is remembered is a no-op`() = runTest {
    val s = store("none")
    s.forgetGroup(7)
    assertThat(s.lastGroupId.first()).isEqualTo(-1L)
  }
}
