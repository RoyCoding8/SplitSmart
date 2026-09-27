package com.splitsmart.ui.vm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.splitsmart.data.Category
import com.splitsmart.data.DbTest
import com.splitsmart.data.Expense
import com.splitsmart.data.Group
import com.splitsmart.data.GroupDefaultsStore
import com.splitsmart.data.Member
import com.splitsmart.data.SplitInput
import com.splitsmart.data.SplitType
import com.splitsmart.data.convertMinor
import com.splitsmart.data.pickRate
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The date is not cosmetic: `pickRate` picks the rate effective at the expense's own date,
// so writing "now" on every save re-prices the expense whenever the rate table has moved on
// since it was entered. There is no date field in the editor, so re-dating can only come
// from the save. This calls the real [ExpenseVm.save], not a restatement of its logic,
// because the thing being pinned is what the model writes.
@RunWith(RobolectricTestRunner::class)
class EditKeepsDateTest : DbTest() {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()

  private lateinit var file: File
  private lateinit var defaults: GroupDefaultsStore

  @Before
  fun setUpStore() {
    file = File(ctx.filesDir, "edit_keeps_date.preferences_pb")
    file.delete()
    defaults = GroupDefaultsStore(PreferenceDataStoreFactory.create { file })
    Dispatchers.setMain(Dispatchers.Unconfined)
  }

  @After
  fun tearDownStore() {
    Dispatchers.resetMain()
    runCatching { file.delete() }
  }

  // `vm.save` is a `viewModelScope.launch`, so the call returns before the write has landed
  // and reading the row straight afterwards is a race that fails as an empty list.
  // `onDone` is the model's own end-of-save signal. Awaited on a real dispatcher: a
  // virtual-time timeout does not advance while the real Room write is in flight.
  private suspend fun awaitSave(onDone: CompletableDeferred<Unit>) {
    withContext(Dispatchers.Default.limitedParallelism(1)) {
      withTimeout(10_000) { onDone.await() }
    }
  }

  private suspend fun group(): Triple<Long, Long, Long> {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val a = repo.saveMember(Member(groupId = g, name = "A", createdAt = now))
    val b = repo.saveMember(Member(groupId = g, name = "B", createdAt = now))
    return Triple(g, a, b)
  }

  @Test
  fun `a note correction does not re-date the expense`() = runTest {
    val (g, a, b) = group()
    val vm = ExpenseVm(repo, defaults)
    val march = now - 200L * 86_400_000L
    val saved = CompletableDeferred<Unit>()

    vm.save(
        gid = g,
        payer = a,
        total = 1000,
        cat = Category.FOOD,
        note = "dinnr",
        rows = listOf(SplitRow(a, "A"), SplitRow(b, "B")),
        type = SplitType.EQUAL,
        cur = "USD",
        dateEpoch = march,
        onDone = { saved.complete(Unit) })
    awaitSave(saved)

    val firstList = repo.expenses(g).first()
    assertThat(firstList).hasSize(1)
    val first = firstList.single()
    assertThat(first.note).isEqualTo("dinnr")
    assertThat(first.dateEpoch).isEqualTo(march)

    // The same save again, with the note spelled properly. The date the caller
    // holds is the one it was given when the expense was loaded; nothing on this
    // path replaces it with the clock.
    val saved2 = CompletableDeferred<Unit>()
    vm.save(
        gid = g,
        payer = a,
        total = 1000,
        cat = Category.FOOD,
        note = "dinner",
        rows = listOf(SplitRow(a, "A"), SplitRow(b, "B")),
        type = SplitType.EQUAL,
        cur = "USD",
        eid = first.id,
        dateEpoch = first.dateEpoch,
        onDone = { saved2.complete(Unit) })
    awaitSave(saved2)

    val afterList = repo.expenses(g).first()
    assertThat(afterList).hasSize(1)
    val after = afterList.single()
    assertThat(after.id).isEqualTo(first.id)
    assertThat(after.note).isEqualTo("dinner")
    assertThat(after.dateEpoch).isEqualTo(march)
  }

  @Test
  fun `a foreign expense keeps the rate it was priced at`() = runTest {
    val (g, a, b) = group()
    val vm = ExpenseVm(repo, defaults)
    val march = now - 200L * 86_400_000L
    val saved = CompletableDeferred<Unit>()

    vm.save(
        gid = g,
        payer = a,
        total = 1000,
        cat = Category.FOOD,
        note = "dinner",
        rows = listOf(SplitRow(a, "A"), SplitRow(b, "B")),
        type = SplitType.EQUAL,
        cur = "EUR",
        dateEpoch = march,
        onDone = { saved.complete(Unit) })
    awaitSave(saved)

    // The March rate, then a June row for the same pair, as a user who corrects
    // a rate table would leave it.
    repo.saveRate(
        com.splitsmart.data.FxRate(groupId = g, fromCode = "EUR", toCode = "USD", rate = 1.10, timeEpoch = march))
    repo.saveRate(
        com.splitsmart.data.FxRate(groupId = g, fromCode = "EUR", toCode = "USD", rate = 1.20, timeEpoch = now))

    val stored = repo.expenses(g).first().single()
    // Open the editor, fix nothing, save.
    val saved2 = CompletableDeferred<Unit>()
    vm.save(
        gid = g,
        payer = a,
        total = stored.amountMinor,
        cat = stored.category,
        note = stored.note,
        rows = listOf(SplitRow(a, "A"), SplitRow(b, "B")),
        type = SplitType.EQUAL,
        cur = "EUR",
        eid = stored.id,
        dateEpoch = stored.dateEpoch,
        onDone = { saved2.complete(Unit) })
    awaitSave(saved2)

    val rates = repo.rates(g).first()
    val afterList = repo.expenses(g).first()
    assertThat(afterList).hasSize(1)
    val after = afterList.single()
    // Worth what it was worth, not what the newest rate says.
    assertThat(convertMinor(after.amountMinor, pickRate(rates, "EUR", "USD", after.dateEpoch)))
        .isEqualTo(1100L)
  }
}
