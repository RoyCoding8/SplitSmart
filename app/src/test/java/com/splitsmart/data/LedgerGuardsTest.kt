package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The writes that refuse: a snapshot that no longer adds up, a member something still
// points at, a recurring rule that has gone stale. Each is a row the user can no longer edit
// their way out of, so the refusal is the behaviour worth pinning.
@RunWith(RobolectricTestRunner::class)
class LedgerGuardsTest : DbTest() {
  private val jan1 = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()

  private suspend fun trio(at: Long = now): Triple<Long, Long, Long> {
    val (g, members) = repo.newGroup(at, "A", "B")
    val (a, b) = members
    return Triple(g, a, b)
  }

  private suspend fun template(
      gid: Long,
      payer: Long,
      amountMinor: Long = 100,
      frequency: Frequency = Frequency.DAILY,
      dueAt: Long = jan1,
      rule: String = SplitRepository.encodeRule(SplitType.EQUAL, mapOf(), mapOf())
  ) =
      repo.saveTemplate(
          RecurringTemplate(
              groupId = gid,
              payerId = payer,
              amountMinor = amountMinor,
              category = Category.FOOD,
              splitRule = rule,
              frequency = frequency,
              nextDueEpoch = dueAt))

  @Test
  fun `raw plan credits every payer`() = runTest {
    val (g, a, b) = trio()
    val c = repo.saveMember(Member(groupId = g, name = "C", createdAt = now))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            currencyCode = "USD",
            category = Category.FOOD,
            dateEpoch = now,
            createdAt = now),
        listOf(c),
        SplitInput(SplitType.EQUAL),
        mapOf(a to 60L, b to 40L),
    )
    assertThat(repo.plan(g, false))
        .containsExactly(
            com.splitsmart.core.settle.Transfer(c, a, 60L, "USD"),
            com.splitsmart.core.settle.Transfer(c, b, 40L, "USD"),
        )
  }

  @Test
  fun `restore rejects corrupt sums and stale members`() = runTest {
    val (g, a, b) = trio()
    repo.charge(g, a, 9000, listOf(a, b), now)
    val eid = db.expenses().expenses(g).first().single().id
    val snap = repo.deleteExpenseWithSnapshot(eid)
    try {
      repo.restoreSnapshot(snap.copy(expense = snap.expense.copy(amountMinor = 1L)))
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
    try {
      repo.restoreSnapshot(snap.copy(shares = listOf(ExpenseShare(0, 999L, 9000L))))
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
    assertThat(db.expenses().expenses(g).first()).isEmpty()
    repo.restoreSnapshot(snap)
    assertThat(db.expenses().expenses(g).first()).hasSize(1)
  }

  @Test
  fun `delete blocked by settlement template subgroup and self refs`() = runTest {
    val (g, a, b) = trio()
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            currencyCode = "USD",
            category = Category.OTHER,
            dateEpoch = now,
            createdAt = now),
        listOf(a, b),
        SplitInput(SplitType.EQUAL))
    val c1 = repo.saveMember(Member(groupId = g, name = "C1", createdAt = now))
    val c2 = repo.saveMember(Member(groupId = g, name = "C2", createdAt = now))
    val c3 = repo.saveMember(Member(groupId = g, name = "C3", createdAt = now))
    val c4 = repo.saveMember(Member(groupId = g, name = "C4", createdAt = now))
    // B owes 100 to A from the expense above, so a settlement from B to c1 leaves
    // B still in debt and the reference row the later deletion checks need.
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = c1, amountMinor = 10, dateEpoch = now))
    template(g, c2)
    repo.saveSubgroup(g, "sg", listOf(c3))
    repo.saveGroup(
        Group(id = g, name = "T", currencyCode = "USD", selfMemberId = c4, createdAt = now))
    listOf(c1, c2, c3, c4).forEach { id ->
      try {
        repo.deleteMember(id)
        assert(false) { "expected for $id" }
      } catch (_: IllegalArgumentException) {}
    }
  }

  @Test
  fun `generateDue skips already-generated period without duplicating`() = runTest {
    val (g, a, _) = trio(jan1)
    val tid = template(g, a)
    // An expense the template already paid for on this date. The period is
    // generated at most once, and the row that proves it is the one carrying the
    // template's id, so the fixture has to be that row rather than a plain bill.
    repo.charge(g, a, 100, listOf(a), jan1)
    val existing = repo.expenses(g).first().single()
    db.expenses().update(existing.copy(recurringId = tid))

    assertThat(repo.generateDue(g, jan1)).isEqualTo(0)
    assertThat(repo.expenses(g).first()).hasSize(1)
  }

  @Test
  fun `stale rule member fails closed without rewriting split`() = runTest {
    val (g, a, b) = trio(jan1)
    template(
        g, a, rule = SplitRepository.encodeRule(SplitType.SHARES, mapOf(a to 1L, b to 1L), mapOf()))
    repo.deleteMember(b)

    assertThat(repo.generateDue(g, jan1)).isEqualTo(0)
    assertThat(repo.expenses(g).first()).isEmpty()
    // The schedule did not move, so the period is still owed once the rule can be
    // applied again. A template that advanced past a period it skipped pays for
    // nothing on the next run.
    assertThat(repo.templates(g).first().single().nextDueEpoch).isEqualTo(jan1)
  }

  @Test
  fun `saveTemplate rejects bad amount and garbage rule`() = runTest {
    val (g, a, _) = trio()
    val good = SplitRepository.encodeRule(SplitType.EQUAL, mapOf(), mapOf())
    assertThat(template(g, a, amountMinor = 100, dueAt = now, rule = good)).isGreaterThan(0L)
    try {
      template(g, a, amountMinor = 0, dueAt = now, rule = good)
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
    try {
      template(g, a, amountMinor = 100, dueAt = now, rule = "garbage")
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
  }

  @Test
  fun `saveSubgroup rejects empty ids and foreign sid`() = runTest {
    val (g, a, b) = trio()
    val g2 = repo.saveGroup(Group(name = "U", currencyCode = "USD", createdAt = now))
    val sg2 =
        repo.saveSubgroup(
            g2, "other", listOf(repo.saveMember(Member(groupId = g2, name = "Z", createdAt = now))))
    try {
      repo.saveSubgroup(g, "empty", emptyList())
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
    try {
      repo.saveSubgroup(g, "hijack", listOf(a, b), sg2)
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
    assertThat(repo.subgroups(g2).first().single().name).isEqualTo("other")
  }
}
