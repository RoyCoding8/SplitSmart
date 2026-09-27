package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportTest : DbTest() {
  private val exp: Exporter by lazy { Exporter(repo) }

  @Test
  fun `backup restore round trip preserves ledger`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 10000, listOf(a, b), now, note = "dinner")
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 1000, dateEpoch = now))
    val json = exp.backup()
    assertThat(exp.csv(exp.dumpGroup(g))).contains("dinner")
    db.groups().delete(g)
    assertThat(exp.restore(json).restored).isEqualTo(1)
    val gid = repo.groups().first().single().id
    val plan = repo.plan(gid, true)
    assertThat(plan.single().amountMinor).isEqualTo(4000L)
  }

  // A backup whose payer name no longer resolves must not restore that expense, and the
  // return value has to say so rather than reporting the run as clean.
  @Test
  fun `restore names the rows it had to drop`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 10000, listOf(a, b), now, note = "keep me")
    repo.charge(g, b, 2500, listOf(a, b), now, note = "drop me")
    val json = exp.backup()
    // Rename B in the payload only, so the member list no longer has him and the
    // second expense's payer cannot resolve.
    val tainted = json.replace("\"payer\":\"B\"", "\"payer\":\"Ghost\"")
    assertThat(tainted).isNotEqualTo(json)
    db.groups().delete(g)

    val r = exp.restore(tainted)
    assertThat(r.restored).isEqualTo(1)
    assertThat(r.skipped).hasSize(1)
    assertThat(r.skipped.single()).contains("drop me")
    // The one that did restore is intact, so the count is not just "one fewer try".
    assertThat(repo.expenses(repo.groups().first().single().id).first().single().note)
        .isEqualTo("keep me")
  }

  @Test
  fun `restore skips corrupt rows without aborting`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 10000, listOf(a, b), now)
    val good = exp.backup()
    db.groups().delete(g)
    val tainted =
        good.replaceFirst(
            "\"payer\":\"A\",\"amountMinor\":10000,\"currencyCode\":\"USD\"",
            "\"payer\":\"A\",\"amountMinor\":10000,\"currencyCode\":\"XX\"")
    assertThat(tainted).isNotEqualTo(good)
    // The corrupt expense is dropped, not half-restored, and the group itself is still
    // there. Checking only that nothing threw would make a restore that silently swallowed
    // the loss look exactly like a clean one.
    val r = exp.restore(tainted)
    assertThat(r.restored).isEqualTo(0)
    assertThat(r.skipped).hasSize(1)
    assertThat(r.skipped.single()).contains("XX")
    assertThat(repo.groups().first()).hasSize(1)
  }
}
