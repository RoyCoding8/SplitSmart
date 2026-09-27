package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecurringTest : DbTest() {
  private val jan1 = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()
  private val day = 86_400_000L

  private suspend fun trio(): Triple<Long, Long, Long> {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = jan1))
    val a = repo.saveMember(Member(groupId = g, name = "A", createdAt = jan1))
    val b = repo.saveMember(Member(groupId = g, name = "B", createdAt = jan1))
    return Triple(g, a, b)
  }

  private fun rule(
      type: SplitType = SplitType.EQUAL,
      values: Map<Long, Long> = mapOf(),
      bps: Map<Long, Int> = mapOf()
  ) = SplitRepository.encodeRule(type, values, bps)

  @Test
  fun `due weekly template generates equal-split expense and advances`() = runTest {
    val (g, a, b) = trio()
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = a,
            amountMinor = 9000,
            category = Category.RENT,
            splitRule = rule(),
            frequency = Frequency.WEEKLY,
            nextDueEpoch = jan1))
    assertThat(repo.generateDue(g, jan1)).isEqualTo(1)
    val exps = repo.expenses(g).first()
    assertThat(exps).hasSize(1)
    assertThat(exps.single().dateEpoch).isEqualTo(jan1)
    assertThat(exps.single().recurringId).isNotNull()
    assertThat(repo.nets(g).first()).containsExactlyEntriesIn(mapOf(a to 4500L, b to -4500L))
    assertThat(repo.templates(g).first().single().nextDueEpoch).isEqualTo(jan1 + 7 * day)
    assertThat(repo.generateDue(g, jan1)).isEqualTo(0)
  }

  @Test
  fun `catches up missed periods up to cap`() = runTest {
    val (g, a, _) = trio()
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            category = Category.FOOD,
            splitRule = rule(),
            frequency = Frequency.DAILY,
            nextDueEpoch = jan1 - 20 * day))
    assertThat(repo.generateDue(g, jan1)).isEqualTo(12)
    assertThat(repo.expenses(g).first()).hasSize(12)
    assertThat(repo.templates(g).first().single().nextDueEpoch).isEqualTo(jan1 - 8 * day)
  }

  @Test
  fun `inactive and future templates untouched`() = runTest {
    val (g, a, _) = trio()
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            category = Category.FOOD,
            splitRule = rule(),
            frequency = Frequency.DAILY,
            nextDueEpoch = jan1,
            active = false))
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            category = Category.FOOD,
            splitRule = rule(),
            frequency = Frequency.DAILY,
            nextDueEpoch = jan1 + day))
    assertThat(repo.generateDue(g, jan1)).isEqualTo(0)
    assertThat(repo.expenses(g).first()).isEmpty()
  }

  @Test
  fun `monthly advance clamps month end`() = runTest {
    val (g, a, _) = trio()
    val jan31 = Instant.parse("2024-01-31T00:00:00Z").toEpochMilli()
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            category = Category.RENT,
            splitRule = rule(),
            frequency = Frequency.MONTHLY,
            nextDueEpoch = jan31))
    assertThat(repo.generateDue(g, jan31)).isEqualTo(1)
    assertThat(repo.templates(g).first().single().nextDueEpoch)
        .isEqualTo(Instant.parse("2024-02-29T00:00:00Z").toEpochMilli())
  }

  /**
   * Catching up is not one expense per missed period by a fixed step. A monthly
   * bill dated on the 31st has no February the 31st to fall on, so the occurrences
   * it generates walk the clamped month ends -- Jan 31, Feb 29, Mar 31 -- and the
   * next due date lands on the April the day it is short of. Anchoring to "31
   * days later" instead silently drifts the whole schedule.
   */
  @Test
  fun `monthly generation stays anchored to original day`() = runTest {
    val (g, a, _) = trio()
    val jan31 = Instant.parse("2024-01-31T00:00:00Z").toEpochMilli()
    val mar31 = Instant.parse("2024-03-31T00:00:00Z").toEpochMilli()
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            category = Category.RENT,
            splitRule = rule(),
            frequency = Frequency.MONTHLY,
            nextDueEpoch = jan31))

    assertThat(repo.generateDue(g, mar31)).isEqualTo(3)
    assertThat(repo.expenses(g).first().map { it.dateEpoch })
        .containsExactly(
            jan31,
            Instant.parse("2024-02-29T00:00:00Z").toEpochMilli(),
            mar31,
        )
    assertThat(repo.templates(g).first().single().nextDueEpoch)
        .isEqualTo(Instant.parse("2024-04-30T00:00:00Z").toEpochMilli())
  }

  @Test
  fun `exact rule honored and stale payer skipped`() = runTest {
    val (g, a, b) = trio()
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = b,
            amountMinor = 1000,
            category = Category.FOOD,
            splitRule = rule(SplitType.EXACT, mapOf(a to 700L, b to 300L)),
            frequency = Frequency.WEEKLY,
            nextDueEpoch = jan1))
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = 999L,
            amountMinor = 500,
            category = Category.FOOD,
            splitRule = rule(),
            frequency = Frequency.WEEKLY,
            nextDueEpoch = jan1))
    assertThat(repo.generateDue(g, jan1)).isEqualTo(1)
    assertThat(repo.nets(g).first()).containsExactlyEntriesIn(mapOf(a to -700L, b to 700L))
    assertThat(repo.templates(g).first().count { it.nextDueEpoch == jan1 }).isEqualTo(1)
  }
}
