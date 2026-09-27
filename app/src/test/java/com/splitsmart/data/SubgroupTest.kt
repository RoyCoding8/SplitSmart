package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubgroupTest : DbTest() {

  @Test
  fun `subgroup preset round trip filters members`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B", "C")
    val (a, b, _) = members
    val sg = repo.saveSubgroup(g, "drinkers", listOf(a, b))
    assertThat(repo.subgroups(g).first().single().name).isEqualTo("drinkers")
    assertThat(repo.subgroupMembers(sg)).containsExactly(a, b)
    repo.charge(g, a, 6000, repo.subgroupMembers(sg), now)
    assertThat(repo.nets(g).first()).containsExactlyEntriesIn(mapOf(a to 3000L, b to -3000L))
  }

  @Test
  fun `subgroup update rename members delete`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    val sg = repo.saveSubgroup(g, "x", listOf(a))
    repo.saveSubgroup(g, "y", listOf(a, b), sg)
    assertThat(repo.subgroups(g).first().single().name).isEqualTo("y")
    assertThat(repo.subgroupMembers(sg)).containsExactly(a, b)
    repo.deleteSubgroup(sg)
    assertThat(repo.subgroups(g).first()).isEmpty()
  }
}
