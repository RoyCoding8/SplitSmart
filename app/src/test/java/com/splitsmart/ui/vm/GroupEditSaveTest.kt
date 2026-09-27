package com.splitsmart.ui.vm

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.splitsmart.data.DbTest
import com.splitsmart.data.Member
import com.splitsmart.data.SettingsPrefsStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
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

// The editor lists a member, can remove one, and has no control that changes a name. Writing
// every listed row back through `saveMember` is a full entity UPDATE for a row that has an id,
// so whatever the editor's copy of the member was missing got written over the real row --
// and the copy comes back from saved instance state as a stub.
@RunWith(RobolectricTestRunner::class)
class GroupEditSaveTest : DbTest() {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()

  private lateinit var file: File
  private lateinit var prefs: SettingsPrefsStore

  @Before
  fun setUpStore() {
    file = File(ctx.filesDir, "groupedit_save.preferences_pb")
    file.delete()
    prefs = SettingsPrefsStore(PreferenceDataStoreFactory.create { file })
    Dispatchers.setMain(Dispatchers.Unconfined)
  }

  @After
  fun tearDownStore() {
    Dispatchers.resetMain()
    runCatching { file.delete() }
  }

  @Test
  fun `saving the editor leaves an existing member's own fields alone`() = runTest {
    val gid = repo.friendGroup("Trip", "USD")
    val member = repo.saveMember(Member(groupId = gid, name = "Robin", createdAt = now))
    repo.setNudge(member, true)
    repo.saveMember(repo.member(member)!!.copy(avatarPath = "/data/robin.jpg", colorSeed = 3))

    val vm = GroupEditVm(repo, prefs, ApplicationProvider.getApplicationContext())
    // The stub the saved instance state hands back: an id and a name, and nothing
    // else. This is the whole of the input that used to destroy the other fields.
    val finished = CompletableDeferred<Unit>()
    vm.save(
        "Trip",
        "USD",
        0L,
        listOf(Member(id = member, groupId = 0L, name = "Robin", createdAt = 0L)),
        gid,
        "USD") { finished.complete(Unit) }
    finished.await()

    val after = repo.member(member)!!
    assertThat(after.avatarPath).isEqualTo("/data/robin.jpg")
    assertThat(after.colorSeed).isEqualTo(3)
    assertThat(after.settleNudge).isTrue()
  }

  @Test
  fun `a member typed on the editor is still inserted`() = runTest {
    val gid = repo.friendGroup("Trip", "USD")
    val before = repo.members(gid).first().map { it.name }

    val vm = GroupEditVm(repo, prefs, ApplicationProvider.getApplicationContext())
    val finished = CompletableDeferred<Unit>()
    vm.save(
        "Trip",
        "USD",
        0L,
        listOf(Member(groupId = 0L, name = "Robin", createdAt = now)),
        gid,
        "USD") { finished.complete(Unit) }
    finished.await()

    // `friendGroup` seeds members of its own, so the claim is that this one is
    // added to what was there rather than that it is the only one.
    assertThat(repo.members(gid).first().map { it.name }).containsExactlyElementsIn(before + "Robin")
  }

  // Two members may share a name, so the self identity cannot be one. Resolving it back to
  // an id with `associate` keeps the last match, and with two Alexes in a group picking the
  // first makes the user the second while the picker lights both radios.
  @Test
  fun `picking one of two same-named members sets that one as self`() = runTest {
    val gid = repo.friendGroup("Trip", "USD")
    val first = repo.saveMember(Member(groupId = gid, name = "Alex", createdAt = now))
    val second = repo.saveMember(Member(groupId = gid, name = "Alex", createdAt = now + 1))

    val vm = GroupEditVm(repo, prefs, ApplicationProvider.getApplicationContext())
    val finished = CompletableDeferred<Unit>()
    vm.save(
        "Trip",
        "USD",
        first,
        listOf(
            Member(id = first, groupId = 0L, name = "Alex", createdAt = 0L),
            Member(id = second, groupId = 0L, name = "Alex", createdAt = 0L)),
        gid,
        "USD") { finished.complete(Unit) }
    finished.await()

    assertThat(repo.group(gid).first()!!.selfMemberId).isEqualTo(first)
  }

  @Test
  fun `a member typed on the editor can be the one set as self`() = runTest {
    val gid = repo.friendGroup("Trip", "USD")
    val existing = repo.members(gid).first().map { it.name }

    val vm = GroupEditVm(repo, prefs, ApplicationProvider.getApplicationContext())
    val finished = CompletableDeferred<Unit>()
    // -1 is the id the screen hands a row it has typed but not yet saved, which
    // is what lets the picker select it at all. The seeded members come first,
    // as they are on screen.
    vm.save(
        "Trip",
        "USD",
        -1L,
        repo.members(gid)
            .first()
            .map { Member(id = it.id, groupId = 0L, name = it.name, createdAt = 0L) } +
            listOf(Member(id = -1L, groupId = 0L, name = "Robin", createdAt = now)),
        gid,
        "USD") { finished.complete(Unit) }
    finished.await()

    val saved = repo.members(gid).first()
    assertThat(saved.map { it.name }).containsExactlyElementsIn(existing + "Robin")
    // The identity has to be the row that was typed, and it has to be a real row.
    assertThat(repo.group(gid).first()!!.selfMemberId)
        .isEqualTo(saved.first { it.name == "Robin" }.id)
  }
}
