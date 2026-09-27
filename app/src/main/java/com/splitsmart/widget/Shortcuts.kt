package com.splitsmart.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.splitsmart.MainActivity
import com.splitsmart.data.SettingsPrefsStore
import com.splitsmart.di.store
import kotlinx.coroutines.flow.first

object ShortcutActions {
  const val ADD_EXPENSE = "com.splitsmart.ADD_EXPENSE"
  const val ADD_GROUP = "com.splitsmart.ADD_GROUP"
  const val OPEN_SEARCH = "com.splitsmart.OPEN_SEARCH"
  const val EXTRA_GID = "gid"
}

object ShortcutHelper {
  /**
   * The one dynamic shortcut's id. A single slot, so it always names whichever group
   * was opened last, and the group id travels in the intent rather than in the id
   * itself -- which is why a stale shortcut is matched on the intent.
   */
  const val ID = "continue-group"

  suspend fun lastGroupId(ctx: Context): Long = SettingsPrefsStore(ctx.store).lastGroupId.first()

  suspend fun onGroupOpened(ctx: Context, gid: Long, name: String) {
    if (gid <= 0) return
    SettingsPrefsStore(ctx.store).setLastGroup(gid)
    runCatching {
      val intent =
          Intent(ctx, MainActivity::class.java)
              .setAction(ShortcutActions.ADD_EXPENSE)
              .putExtra(ShortcutActions.EXTRA_GID, gid)
      val short = if (name.length > 12) name.take(11) + "…" else name
      val info =
          ShortcutInfoCompat.Builder(ctx, ID)
              .setShortLabel(short)
              .setLongLabel("Add expense in $name")
              .setIcon(IconCompat.createWithResource(ctx, android.R.drawable.ic_menu_edit))
              .setIntent(intent)
              .setRank(1)
              .build()
      ShortcutManagerCompat.pushDynamicShortcut(ctx, info)
    }
  }

  /**
   * Drops the shortcut when it holds the group named, so its id cannot outlive the
   * group it points at.
   *
   * The shortcut carries the group id in its own intent, pinned when the group was
   * opened, so [SettingsPrefsStore.forgetGroup] does not reach it. Matching on the
   * intent, and only for the group named, is what keeps an unrelated delete from
   * dropping a perfectly good shortcut.
   */
  suspend fun forgetGroupShortcut(ctx: Context, gid: Long) {
    runCatching {
      val held =
          ShortcutManagerCompat.getDynamicShortcuts(ctx).firstOrNull { it.id == ID }?.intent
              ?.getLongExtra(ShortcutActions.EXTRA_GID, -1L)
      if (held == gid) ShortcutManagerCompat.removeDynamicShortcuts(ctx, listOf(ID))
    }
  }
}
