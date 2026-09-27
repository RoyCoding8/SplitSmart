package com.splitsmart.ui.vm

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.splitsmart.data.Category
import com.splitsmart.data.DbTest
import com.splitsmart.data.Expense
import com.splitsmart.data.Group
import com.splitsmart.data.Member
import com.splitsmart.data.SettingsPrefsStore
import com.splitsmart.data.SplitInput
import com.splitsmart.data.SplitType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The snapshot lives in the view model, whose lifetime is the navigation entry rather
// than the composition. So the delete hands the caller nothing to keep, the undo takes
// the offer, and the promise survives the caller going away.
@RunWith(RobolectricTestRunner::class)
class UndoSurvivesRecreationTest : DbTest() {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()

  private lateinit var file: File
  private lateinit var prefs: SettingsPrefsStore

  @Before
  fun setUpStore() {
    file = File(ctx.filesDir, "undo_survives_recreation.preferences_pb")
    file.delete()
    prefs = SettingsPrefsStore(PreferenceDataStoreFactory.create { file })
    Dispatchers.setMain(Dispatchers.Unconfined)
  }

  @After
  fun tearDownStore() {
    Dispatchers.resetMain()
    runCatching { file.delete() }
  }

  private fun vm() = GroupDetailVm(repo, prefs, ApplicationProvider.getApplicationContext())

  private suspend fun fixture(): Triple<Long, Long, Long> {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val a = repo.saveMember(Member(groupId = g, name = "A", createdAt = now))
    val b = repo.saveMember(Member(groupId = g, name = "B", createdAt = now))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 9000,
            currencyCode = "USD",
            category = Category.FOOD,
            note = "dinner",
            dateEpoch = now,
            createdAt = now),
        listOf(a, b),
        SplitInput(SplitType.EQUAL))
    return Triple(g, a, b)
  }

  // Read the offer the way the screen does, rather than reaching past it for the
  // snapshot: a test that grabbed the snapshot directly could pass while the bar and
  // the snapshot disagreed.
  private fun GroupDetailVm.offered(): UndoOffer? = undoOffer.value

  @Test
  fun `an offer made by one caller is cashed by another`() = runTest {
    val (g, a, _) = fixture()
    val eid = repo.expenses(g).first().single().id
    val vm = vm()
    vm.bind(g)
    val owed = repo.nets(g).first()

    vm.deleteExpense(eid)
    assertThat(repo.expenses(g).first()).isEmpty()
    assertThat(repo.nets(g).first()).doesNotContainKey(a)

    assertThat(vm.undoLastDelete(vm.offered())).isTrue()

    val back = repo.expenses(g).first().single()
    assertThat(back.note).isEqualTo("dinner")
    // The whole ledger, not just the row: an undo that restored the expense
    // without its shares would look restored on screen and leave every balance
    // at zero underneath.
    assertThat(repo.shares(back.id).sumOf { it.owedMinor }).isEqualTo(9000L)
    assertThat(repo.nets(g).first()).isEqualTo(owed)
  }

  // `restoreSnapshot` inserts the row and hands back a fresh id, so restoring the same
  // snapshot twice does not fail loudly -- it silently duplicates the expense. The guard
  // is that the snapshot is taken out of the offer before the restore runs, not after.
  @Test
  fun `the undo offer is consumed once`() = runTest {
    val (g, _, _) = fixture()
    val eid = repo.expenses(g).first().single().id
    val vm = vm()
    vm.bind(g)
    vm.deleteExpense(eid)

    val spent = vm.offered()
    assertThat(spent).isNotNull()
    assertThat(vm.undoLastDelete(spent)).isTrue()
    // The same offer again: the snapshot is out from under it, so there is nothing
    // to cash and no second copy of the expense.
    assertThat(vm.undoLastDelete(spent)).isFalse()
    assertThat(repo.expenses(g).first()).hasSize(1)
  }

  // A declined offer must stay refused: the next delete's Undo, or a stale tap on a bar
  // that is gone, must not bring back an expense the user was told was permanently
  // deleted.
  @Test
  fun `a declined offer does not restore anything`() = runTest {
    val (g, _, _) = fixture()
    val eid = repo.expenses(g).first().single().id
    val vm = vm()
    vm.bind(g)
    vm.deleteExpense(eid)
    // Held from before the decline: a stale tap on a bar that is gone must not
    // restore what the user was told was permanently deleted.
    val stale = vm.offered()
    assertThat(stale).isNotNull()
    vm.declineUndo()

    assertThat(vm.undoLastDelete(stale)).isFalse()
    assertThat(repo.expenses(g).first()).isEmpty()
  }

  // One slot, not a queue. The user deleted two things and tapped Undo once; the one
  // they mean is the last, which is what the bar on screen is offering.
  @Test
  fun `a second delete replaces the offer`() = runTest {
    val (g, _, _) = fixture()
    val first = repo.expenses(g).first().single().id
    val a = repo.members(g).first().first().id
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 500,
            currencyCode = "USD",
            category = Category.OTHER,
            note = "second",
            dateEpoch = now + 1,
            createdAt = now + 1),
        listOf(a),
        SplitInput(SplitType.EQUAL))

    val vm = vm()
    vm.bind(g)
    vm.deleteExpense(first)
    vm.deleteExpense(repo.expenses(g).first().single { it.note == "second" }.id)
    assertThat(repo.expenses(g).first()).isEmpty()

    assertThat(vm.undoLastDelete(vm.offered())).isTrue()
    // Only the second comes back. The first was superseded, not declined by a tap.
    assertThat(repo.expenses(g).first().single().note).isEqualTo("second")
  }

  // The offer is the snapshot and the message together, so a second delete is a
  // different value, re-keys the effect, and gets its own bar. The bar that is on
  // screen is the one whose snapshot comes back.
  @Test
  fun `the bar on screen restores the expense it was shown for`() = runTest {
    val (g, a, _) = fixture()
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 500,
            currencyCode = "USD",
            category = Category.OTHER,
            note = "second",
            dateEpoch = now + 1,
            createdAt = now + 1),
        listOf(a),
        SplitInput(SplitType.EQUAL))
    val dinnerId = repo.expenses(g).first().single { it.note == "dinner" }.id
    val secondId = repo.expenses(g).first().single { it.note == "second" }.id

    val vm = vm()
    vm.bind(g)

    // Delete A. The bar now on screen offers A.
    vm.deleteExpense(dinnerId)
    val barForDinner = vm.offered()
    assertThat(barForDinner).isNotNull()

    // Delete B before the bar is acted on. The bar for A has not been dismissed,
    // so it is still the one the user is looking at -- and this is a different
    // offer, so it gets its own bar rather than conflating with A's.
    vm.deleteExpense(secondId)
    val barForSecond = vm.offered()
    assertThat(barForSecond).isNotNull()
    assertThat(barForSecond).isNotSameInstanceAs(barForDinner)

    // Acting on the first bar -- the one still on screen -- restores A.
    assertThat(vm.undoLastDelete(barForDinner)).isTrue()
    assertThat(repo.expenses(g).first().single().note).isEqualTo("dinner")
  }

  // Undo with nothing pending is a no-op, not a restore of somebody else's expense.

  @Test
  fun `undo with no offer behind it restores nothing`() = runTest {
    val (g, _, _) = fixture()
    val vm = vm()
    vm.bind(g)

    // A screen holding no offer has nothing to pass, so the call arrives null.
    assertThat(vm.undoLastDelete(vm.offered())).isFalse()
    assertThat(repo.expenses(g).first()).hasSize(1)
  }

  // A bar a coroutine sent is gone after a rotation; an offer the screen reads is
  // re-shown the moment the screen is built again. So the offer's presence has to
  // appear and disappear with the snapshot exactly.
  @Test
  fun `the offer is state, and it tracks the snapshot`() = runTest {
    val (g, _, _) = fixture()
    val eid = repo.expenses(g).first().single().id
    val vm = vm()
    vm.bind(g)

    assertThat(vm.undoOffer.value).isNull()

    vm.deleteExpense(eid)
    val offered = vm.undoOffer.value
    assertThat(offered).isNotNull()
    // The label and the snapshot are one thing: an offer whose text outlives
    // what it offers is a bar promising an undo that cannot be taken.
    assertThat(offered!!.message).contains("deleted")

    assertThat(vm.undoLastDelete(offered)).isTrue()
    assertThat(vm.undoOffer.value).isNull()

    val second = repo.saveExpense(
        Expense(
            groupId = g,
            payerId = repo.members(g).first().first().id,
            amountMinor = 500,
            currencyCode = "USD",
            category = Category.OTHER,
            note = "second",
            dateEpoch = now + 1,
            createdAt = now + 1),
        listOf(repo.members(g).first().first().id),
        SplitInput(SplitType.EQUAL))
    vm.deleteExpense(second)
    assertThat(vm.undoOffer.value).isNotNull()
    vm.declineUndo()
    assertThat(vm.undoOffer.value).isNull()
  }
}
