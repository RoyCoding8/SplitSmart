package com.splitsmart.ui.vm

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.splitsmart.data.DbTest
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

/**
 * The same delete, through the door that was missing it.
 *
 * `remembering a group and then deleting it by the group-editor button` used to
 * leave the id behind, because that ViewModel had never been handed the pref. The
 * pref's own behaviour is covered in the data-layer test; what is pinned here is
 * that this caller reaches it.
 */
@RunWith(RobolectricTestRunner::class)
class GroupEditDeleteTest : DbTest() {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()

  private lateinit var file: File
  private lateinit var prefs: SettingsPrefsStore

  @Before
  fun setUpStore() {
    file = File(ctx.filesDir, "groupedit_delete.preferences_pb")
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
  fun `editor delete forgets the remembered group`() = runTest {
    val gid = repo.friendGroup("Trip", "USD")
    prefs.setLastGroup(gid)
    assertThat(prefs.lastGroupId.first()).isEqualTo(gid)

    val vm = GroupEditVm(repo, prefs, ApplicationProvider.getApplicationContext())
    val finished = CompletableDeferred<Unit>()
    vm.deleteGroup(gid) { finished.complete(Unit) }
    finished.await()

    assertThat(prefs.lastGroupId.first()).isEqualTo(-1L)
  }
}
