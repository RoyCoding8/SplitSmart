package com.splitsmart.data

import android.content.Context
import androidx.datastore.preferences.core.*
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
class GroupDefaultsTest {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()

  private val files = mutableListOf<File>()

  private fun store(name: String): GroupDefaultsStore {
    val f = File(ctx.filesDir, "dstest_$name.preferences_pb")
    files += f
    return GroupDefaultsStore(PreferenceDataStoreFactory.create { f })
  }

  @After
  fun clean() {
    files.forEach { runCatching { it.delete() } }
  }

  @Test
  fun `save then load round trips defaults`() = runTest {
    val s = store("rt")
    s.save(
        7,
        GroupDefaults(
            payerId = 3, memberIds = listOf(3, 5), category = Category.FOOD, type = SplitType.SHARES))
    assertThat(s.load(7))
        .isEqualTo(
            GroupDefaults(
                payerId = 3, memberIds = listOf(3, 5), category = Category.FOOD, type = SplitType.SHARES))
  }

  // Older builds stored the last amount per group. A save now clears it, and a load must
  // not resurrect one that survived an upgrade.
  @Test
  fun `a stale last amount is neither read nor kept`() = runTest {
    val f = File(ctx.filesDir, "dstest_amt.preferences_pb")
    files += f
    val raw = PreferenceDataStoreFactory.create { f }
    raw.edit { it[stringPreferencesKey("g1_amt")] = "4200" }
    val s = GroupDefaultsStore(raw)
    assertThat(s.load(1)).isEqualTo(GroupDefaults())
    s.save(1, GroupDefaults(payerId = 1))
    assertThat(raw.data.first()[stringPreferencesKey("g1_amt")]).isNull()
  }

  @Test
  fun `unknown group returns empty defaults`() = runTest {
    assertThat(store("empty").load(99)).isEqualTo(GroupDefaults())
  }

  // Saving the group at all is how the editor says "this group has no defaults yet". A save
  // that left the previous group in place meant opening a group for the first time
  // silently pre-filled the last one used.
  @Test
  fun `a null save clears the stored defaults`() = runTest {
    val s = store("clear")
    s.save(
        1,
        GroupDefaults(
            payerId = 3,
            memberIds = listOf(3),
            category = Category.FOOD,
            type = SplitType.SHARES))
    s.save(1, GroupDefaults())
    assertThat(s.load(1)).isEqualTo(GroupDefaults())
  }

  @Test
  fun `groups are isolated from each other`() = runTest {
    val s = store("iso")
    s.save(1, GroupDefaults(payerId = 1, category = Category.RENT))
    s.save(2, GroupDefaults(payerId = 2, category = Category.FOOD))
    assertThat(s.load(1).payerId).isEqualTo(1L)
    assertThat(s.load(2).category).isEqualTo(Category.FOOD)
  }

  @Test
  fun `corrupt values fall back to safe defaults`() = runTest {
    val f = File(ctx.filesDir, "dstest_corrupt.preferences_pb")
    files += f
    val raw = PreferenceDataStoreFactory.create { f }
    raw.edit {
      it[stringPreferencesKey("g1_cat")] = "NOPE"
      it[stringPreferencesKey("g1_type")] = "NOPE"
      it[stringPreferencesKey("g1_payer")] = "abc"
      it[stringPreferencesKey("g1_members")] = "1,x,2"
    }
    val d = GroupDefaultsStore(raw).load(1)
    assertThat(d.category).isEqualTo(Category.OTHER)
    assertThat(d.type).isEqualTo(SplitType.EQUAL)
    assertThat(d.payerId).isNull()
    assertThat(d.memberIds).containsExactly(1L, 2L)
  }
}
