package com.splitsmart.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The launcher carries one dynamic shortcut, under a single id, holding whichever group was
// opened last, so it is a second copy of the same remembered group id the preferences store
// keeps. The shortcut's intent carries that id as an extra, pinned when the group was
// opened, so clearing the pref does not touch it -- and a shortcut left pointing at a deleted
// group opens an editor with no members to split between and a Save button that can never
// enable.
@RunWith(RobolectricTestRunner::class)
class ShortcutForgetTest {
  private val ctx: Context get() = ApplicationProvider.getApplicationContext()

  private fun pushedGid(): Long? {
    val sm = ctx.getSystemService(android.content.pm.ShortcutManager::class.java)
    val shortcut = sm?.dynamicShortcuts?.firstOrNull { it.id == ShortcutHelper.ID }
    return shortcut?.intent?.getLongExtra(ShortcutActions.EXTRA_GID, -1L)?.takeIf { it > 0 }
  }

  @Test
  fun `a shortcut for the group being deleted is dropped`() = runTest {
    ShortcutHelper.onGroupOpened(ctx, 7L, "Trip")
    assertThat(pushedGid()).isEqualTo(7L)

    ShortcutHelper.forgetGroupShortcut(ctx, 7L)

    assertThat(pushedGid()).isNull()
  }

  @Test
  fun `a shortcut for a different group survives`() = runTest {
    ShortcutHelper.onGroupOpened(ctx, 9L, "Flat")
    ShortcutHelper.onGroupOpened(ctx, 7L, "Trip")
    // Opening the trip replaced the flat's shortcut: there is only one slot, and
    // it now holds the trip.
    assertThat(pushedGid()).isEqualTo(7L)

    ShortcutHelper.forgetGroupShortcut(ctx, 42L)

    assertThat(pushedGid()).isEqualTo(7L)
  }

  @Test
  fun `forgetting a group that was never opened is harmless`() = runTest {
    ShortcutHelper.onGroupOpened(ctx, 7L, "Trip")

    ShortcutHelper.forgetGroupShortcut(ctx, 7L)
    ShortcutHelper.forgetGroupShortcut(ctx, 7L)

    assertThat(pushedGid()).isNull()
  }

  @Test
  fun `the shortcut id is the single slot the group name goes in`() = runTest {
    ShortcutHelper.onGroupOpened(ctx, 7L, "Trip")

    val sm = ctx.getSystemService(android.content.pm.ShortcutManager::class.java)
    val shortcut = sm?.dynamicShortcuts?.firstOrNull { it.id == ShortcutHelper.ID }

    assertThat(shortcut?.shortLabel?.toString()).isEqualTo("Trip")
    assertThat(shortcut?.intent?.action).isEqualTo(ShortcutActions.ADD_EXPENSE)
  }
}
