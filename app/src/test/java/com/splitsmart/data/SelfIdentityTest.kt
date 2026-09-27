package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The group's "you" is not a preference: removing the member it points at cannot
// take it with them, even when the save resolves self from the rows still on screen.
@RunWith(RobolectricTestRunner::class)
class SelfIdentityTest : DbTest() {

  @Test
  fun `removing the group's self is refused and the self survives`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val me = repo.saveMember(Member(groupId = g, name = "Me", createdAt = now))
    val sam = repo.saveMember(Member(groupId = g, name = "Sam", createdAt = now))
    repo.saveGroup(
        Group(id = g, name = "T", currencyCode = "USD", selfMemberId = me, createdAt = now))

    // The user unticks Me, so Me is gone from the rows the editor is about to
    // write and its name resolves to nothing.
    val remaining = listOf(Member(groupId = g, id = sam, name = "Sam", createdAt = now))
    val ids = remaining.associate { it.name to it.id }
    val nameOf = repo.members(g).first().associate { it.id to it.name }
    val stuck =
        listOf(me).mapNotNull { m ->
          runCatching { repo.deleteMember(m) }.exceptionOrNull()?.let { nameOf[m] ?: "$m" }
        }
    assertThat(stuck).containsExactly("Me")

    // With no row on screen to point at, the resolution has to fall back to what
    // the group already had rather than to null.
    val selfId = "Me".let { ids[it] } ?: repo.group(g).first()!!.selfMemberId
    repo.saveGroup(
        repo.group(g).first()!!.copy(id = g, selfMemberId = selfId, createdAt = now))

    val after = repo.group(g).first()!!
    assertThat(after.selfMemberId).isEqualTo(me)
    assertThat(repo.members(g).first().map { it.name }).containsExactly("Me", "Sam")
  }
}
