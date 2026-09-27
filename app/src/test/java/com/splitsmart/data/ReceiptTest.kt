package com.splitsmart.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceiptTest : DbTest() {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()

  @Test
  fun `receipt file naming is scoped and unique`() {
    val a = receiptFile(ctx.filesDir, 12, 1)
    val b = receiptFile(ctx.filesDir, 12, 1)
    assertThat(a.parentFile).isEqualTo(File(ctx.filesDir, "receipts"))
    assertThat(a.name).startsWith("r12_")
    assertThat(a.absolutePath).isNotEqualTo(b.absolutePath)
    assertThat(a.absolutePath).startsWith(ctx.filesDir.absolutePath)
  }

  @Test
  fun `snapshot restore preserves receipt`() = runTest {
    val (g, members) = repo.newGroup(now, "A")
    val a = members.single()
    repo.charge(g, a, 100, listOf(a), now, category = Category.OTHER, receiptUri = "file://r.jpg")
    val eid = db.expenses().expenses(g).first().single().id
    val snap = repo.deleteExpenseWithSnapshot(eid)
    repo.restoreSnapshot(snap)
    assertThat(db.expenses().expenses(g).first().single().receiptUri).isEqualTo("file://r.jpg")
  }
}
