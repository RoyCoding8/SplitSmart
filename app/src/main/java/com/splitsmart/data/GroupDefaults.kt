package com.splitsmart.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

data class GroupDefaults(
    val payerId: Long? = null,
    val memberIds: List<Long> = emptyList(),
    val category: Category = Category.OTHER,
    val type: SplitType = SplitType.EQUAL,
)

class GroupDefaultsStore(private val store: DataStore<Preferences>) {
  private fun k(gid: Long, s: String) = stringPreferencesKey("g${gid}_$s")

  suspend fun load(gid: Long): GroupDefaults {
    val p = store.data.first()
    return GroupDefaults(
        payerId = p[k(gid, "payer")]?.toLongOrNull(),
        memberIds =
            p[k(gid, "members")]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
        category =
            p[k(gid, "cat")]?.let {
              runCatching { Category.valueOf(it) }.getOrDefault(Category.OTHER)
            } ?: Category.OTHER,
        type =
            p[k(gid, "type")]?.let {
              runCatching { SplitType.valueOf(it) }.getOrDefault(SplitType.EQUAL)
            } ?: SplitType.EQUAL,
    )
  }

  suspend fun save(gid: Long, d: GroupDefaults) {
    store.edit {
      if (d.payerId == null) it.remove(k(gid, "payer"))
      else it[k(gid, "payer")] = d.payerId.toString()
      it[k(gid, "members")] = d.memberIds.joinToString(",")
      it[k(gid, "cat")] = d.category.name
      it[k(gid, "type")] = d.type.name
      // The editor has no amount prefill, so an `amt` key from an older build is dropped.
      it.remove(k(gid, "amt"))
    }
  }
}
