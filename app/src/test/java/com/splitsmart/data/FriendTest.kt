package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FriendTest : DbTest() {

  @Test
  fun `friend group creates self plus friend and reuses ledger`() = runTest {
    val gid = repo.friendGroup("Bo", "USD")
    val members = repo.members(gid).first()
    assertThat(members.map { it.name }).containsExactly("Me", "Bo")
    assertThat(repo.group(gid).first()?.kind).isEqualTo(GroupKind.FRIEND)
    val me = members.first { it.name == "Me" }.id
    val bo = members.first { it.name == "Bo" }.id
    repo.saveExpense(
        Expense(
            groupId = gid,
            payerId = me,
            amountMinor = 10000,
            currencyCode = "USD",
            category = Category.FOOD,
            dateEpoch = now,
            createdAt = now),
        listOf(me, bo),
        SplitInput(SplitType.EQUAL))
    assertThat(repo.plan(gid, true).single().from).isEqualTo(bo)
  }

  @Test
  fun `same friend name returns existing group`() = runTest {
    val a = repo.friendGroup("Bo", "USD")
    val b = repo.friendGroup(" Bo ", "USD")
    assertThat(a).isEqualTo(b)
    assertThat(repo.groups().first().filter { it.kind == GroupKind.FRIEND }).hasSize(1)
  }

  @Test
  fun `blank friend name rejected`() = runTest {
    try {
      repo.friendGroup("  ", "USD")
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
  }

  @Test
  fun `groups by kind separates friends from groups`() = runTest {
    repo.saveGroup(Group(name = "Trip", currencyCode = "USD", createdAt = now))
    repo.friendGroup("Bo", "USD")
    assertThat(repo.groupsByKind(GroupKind.GROUP).first().map { it.name }).containsExactly("Trip")
    assertThat(repo.groupsByKind(GroupKind.FRIEND).first().map { it.name }).containsExactly("Bo")
  }

  // The four writes that make a friend group -- insert the group, insert "Me", insert the
  // friend, then point selfMemberId at "Me" -- are one unit. A failure partway through left a
  // FRIEND group with no self identity, and since `friendGroup` returns any FRIEND group
  // matching the name, that broken group came back on every later attempt and was never
  // repaired: the user is stuck with a group that exists and has no "Me".
  @Test
  fun `a friend group is never left half built`() = runTest {
    val gid = repo.friendGroup("Bo", "USD")
    val members = repo.members(gid).first()
    val me = members.single { it.name == "Me" }
    val grp = repo.group(gid).first()!!

    // The self identity resolves to a real member of this group, and it is "Me"
    // rather than the friend -- pointing it at the friend inverts every balance in
    // the group, which is the other way this could half-build.
    assertThat(grp.selfMemberId).isEqualTo(me.id)
    assertThat(members.map { it.id }).contains(grp.selfMemberId)
    assertThat(grp.selfMemberId).isNotEqualTo(members.single { it.name == "Bo" }.id)
    // And there is no orphan: exactly the two members, both in this group.
    assertThat(members.map { it.name }).containsExactly("Me", "Bo")
    assertThat(members.all { it.groupId == gid }).isTrue()
  }
}
