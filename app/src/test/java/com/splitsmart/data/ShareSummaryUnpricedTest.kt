package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The share text leaves the app, so a figure in it is a figure someone else reads and
// acts on. A group with a foreign expense and no rate for the pair has its total computed
// from `pickRate`'s parity fallback, which fabricates an exchange rate rather than looking
// one up. The dashboard already refuses to show such a figure; this text must too.
@RunWith(RobolectricTestRunner::class)
class ShareSummaryUnpricedTest : DbTest() {

  /** Ann pays a dinner for two. [rate] enters the EUR→USD rate first, if given. */
  private suspend fun annPaysDinner(amountMinor: Long, currencyCode: String, rate: Double? = null):
      Long {
    val (gid, ids) = MoneyTest.members(repo, now, listOf("Ann", "Bo"))
    rate?.let { MoneyTest.rate(repo, gid, now, "EUR", "USD", it, ageMillis = 0L) }
    MoneyTest.bill(repo, now, gid, ids[0], ids, amountMinor, currencyCode, note = "dinner")
    return gid
  }

  @Test
  fun `a total resting on a missing rate is not stated`() = runTest {
    val gid = annPaysDinner(10_000L, "EUR")

    val s = repo.shareSummary(gid)

    assertThat(s).doesNotContain("Total spent")
    assertThat(s).contains("EUR")
  }

  @Test
  fun `a rate for the pair restores the total`() = runTest {
    val gid = annPaysDinner(10_000L, "EUR", rate = 2.0)

    assertThat(repo.shareSummary(gid)).contains("Total spent")
  }

  @Test
  fun `a group spending only in its own currency is unaffected`() = runTest {
    val gid = annPaysDinner(10_000L, "USD")

    assertThat(repo.shareSummary(gid)).contains("Total spent USD 100.00")
  }
}
