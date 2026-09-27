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
import com.splitsmart.data.GroupDefaults
import com.splitsmart.data.GroupDefaultsStore
import com.splitsmart.data.Member
import com.splitsmart.data.SplitInput
import com.splitsmart.data.SplitType
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

// The editor's defaults and its member list come from two stores that order nothing
// against each other, so the editor has to be able to say which of them it is waiting
// for. The loaded signal is separate, and a stored payer survives the trip.
@RunWith(RobolectricTestRunner::class)
class PrefillRaceTest : DbTest() {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()

  private lateinit var file: File
  private lateinit var defaults: GroupDefaultsStore
  private lateinit var real: DataStore<Preferences>

  @Before
  fun setUpStore() {
    file = File(ctx.filesDir, "prefill_race.preferences_pb")
    file.delete()
    real = PreferenceDataStoreFactory.create { file }
    defaults = GroupDefaultsStore(real)
    Dispatchers.setMain(Dispatchers.Unconfined)
  }

  @After
  fun tearDownStore() {
    Dispatchers.resetMain()
    runCatching { file.delete() }
  }

  /** The view model under test reads the same store the test writes to. */
  private fun vm(): ExpenseVm = ExpenseVm(repo, defaults)

  // Deliberately outside `runTest`'s virtual clock: a virtual-time timeout never advances
  // while the real DataStore read is in flight, so it fires immediately and reports a
  // failure that never happened.
  private suspend fun ExpenseVm.loadAndAwait(gid: Long) {
    loadDefaults(gid)
    withContext(Dispatchers.Default.limitedParallelism(1)) {
      withTimeout(10_000) { prefillLoaded.first { it } }
    }
  }

  private suspend fun group(name: String = "T", vararg who: String): Pair<Long, List<Long>> {
    val g = repo.saveGroup(Group(name = name, currencyCode = "USD", createdAt = now))
    val ids = who.map { repo.saveMember(Member(groupId = g, name = it, createdAt = now)) }
    return g to ids
  }

  // `load` answers an absent row with a defaulted object rather than a null, so the
  // signal and not the value is what distinguishes "read nothing" from "not read yet".
  @Test
  fun `a prefill that has not been read is not the same as one that was not stored`() =
      runTest {
        val vm = vm()
        assertThat(vm.prefill.value).isNull()
        assertThat(vm.prefillLoaded.value).isFalse()

        vm.loadAndAwait(1L)
        assertThat(vm.prefillLoaded.value).isTrue()
        assertThat(vm.prefill.value?.payerId).isNull()
      }

  // The state the race actually takes: the members are in hand and the defaults are
  // still in flight, so the editor has nothing to act on yet.
  @Test
  fun `a members-first arrival does not read as a settled default`() = runTest {
    val (g, ids) = group(who = arrayOf("A", "B"))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = ids[0],
            amountMinor = 1000,
            currencyCode = "USD",
            category = Category.FOOD,
            dateEpoch = now,
            createdAt = now),
        listOf(ids[0]),
        SplitInput(SplitType.EQUAL))

    // The members are there; nothing has asked for the defaults yet.
    assertThat(repo.members(g).first()).hasSize(2)
    val vm = vm()
    assertThat(vm.prefillLoaded.value).isFalse()
  }

  /** The saved payer survives, which is the whole point of the load. */
  @Test
  fun `a stored payer comes back rather than the first member`() = runTest {
    val (g, ids) = group(who = arrayOf("A", "B"))
    val (first, last) = ids[0] to ids[1]
    defaults.save(
        g, GroupDefaults(last, listOf(first, last), Category.TRANSPORT, SplitType.EXACT))

    val vm = vm()
    vm.loadAndAwait(g)

    assertThat(vm.prefill.value?.payerId).isEqualTo(last)
    assertThat(vm.prefillLoaded.value).isTrue()
  }

  // Two loads for different groups are in flight together and the first is released last,
  // so a model that only clears before the read would leave group one standing under
  // group two's members.
  @Test
  fun `the older of two overlapping loads does not land last`() = runTest {
    val (one, oneIds) = group("One", "A", "B")
    val (two, twoIds) = group("Two", "A", "B")
    defaults.save(one, GroupDefaults(oneIds[1], oneIds, Category.FOOD, SplitType.EQUAL))
    defaults.save(two, GroupDefaults(twoIds[1], twoIds, Category.OTHER, SplitType.SHARES))

    // A store whose first read parks until the test releases it, so the interleaving is
    // the one this test is about rather than whichever one the scheduler picks. Only the
    // first collection waits; a later one answers at once, or the second load would queue
    // behind the first and there would be nothing to observe.
    val gate = CompletableDeferred<Unit>()
    val parked = CompletableDeferred<Unit>()
    val reads = AtomicInteger()
    val held =
        GroupDefaultsStore(
            object : DataStore<Preferences> {
              override val data = flow {
                if (reads.getAndIncrement() == 0) {
                  parked.complete(Unit)
                  gate.await()
                }
                emitAll(real.data)
              }

              override suspend fun updateData(
                  transform: suspend (t: Preferences) -> Preferences
              ): Preferences = real.updateData(transform)
            })
    val vm = ExpenseVm(repo, held)

    vm.loadDefaults(one)
    // With the first read parked mid-flight, group one's stored payer changes
    // underneath it, so anything that load publishes afterwards is stale for it.
    withContext(Dispatchers.Default.limitedParallelism(1)) {
      withTimeout(10_000) { parked.await() }
    }
    defaults.save(one, GroupDefaults(oneIds[0], oneIds, Category.FOOD, SplitType.EQUAL))
    vm.loadDefaults(two)
    gate.complete(Unit)

    withContext(Dispatchers.Default.limitedParallelism(1)) {
      withTimeout(10_000) { vm.prefillLoaded.first { it } }
      // Real time, because the read that could still be in flight is a real one on its
      // own thread. Watch for the stale answer rather than sleeping a fixed amount.
      val deadline = System.currentTimeMillis() + 2_000
      while (vm.prefill.value?.payerId != oneIds[0] && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
      }
    }
    assertThat(vm.prefill.value?.payerId).isEqualTo(twoIds[1])
  }
}
