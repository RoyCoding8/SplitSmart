package com.splitsmart.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A dump carries the image twice: `receiptData` is the bytes, base64'd, and is what makes
// a backup portable, while `receipt` is the absolute `file://` path the image happened to
// have when the backup was taken. Falling back from the first to the second is sound for a
// snapshot moving within one install and wrong for every real backup, since a path written
// on the exporting phone does not name a file on this one.
@RunWith(RobolectricTestRunner::class)
class ReceiptLossTest : DbTest() {
  private val ctx: Context
    get() = ApplicationProvider.getApplicationContext()
  private val files = mutableListOf<File>()

  @After fun clean() {
    files.forEach { it.deleteRecursively() }
  }

  @Test
  fun `a receipt whose bytes are not in the file is reported as a loss`() = runTest {
    val dir = File(ctx.cacheDir, "rloss_${System.nanoTime()}").also { it.mkdirs() }
    files += dir
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val a = repo.saveMember(Member(groupId = g, name = "A", createdAt = now))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            currencyCode = "USD",
            category = Category.FOOD,
            receiptUri = "file:///data/user/0/com.splitsmart/files/receipts/r1_1_1.jpg",
            dateEpoch = now,
            createdAt = now),
        listOf(a),
        SplitInput(SplitType.EQUAL))
    // A backup with no filesDir cannot read the image, so the dump carries the path and no
    // bytes: the unrecoverable case, which has to be reported rather than left as a link
    // that opens a broken image with no message anywhere.
    val json = Exporter(repo).backup()
    db.groups().delete(g)

    val dir2 = File(ctx.cacheDir, "rloss2_${System.nanoTime()}").also { it.mkdirs() }
    files += dir2
    val res = Exporter(repo, null, dir2).restore(json)

    assertThat(res.skipped.joinToString()).contains("receipt")
    // And no row is left pointing at a path on another device.
    val gid = repo.allGroups().first().single().id
    assertThat(repo.expenses(gid).first().single().receiptUri).isNull()
  }

  @Test
  fun `a receipt carried as bytes is reported as restored with no loss`() = runTest {
    val dir = File(ctx.cacheDir, "rloss_ok_${System.nanoTime()}").also { it.mkdirs() }
    files += dir
    val bytes = ByteArray(64) { (it * 3).toByte() }
    val src = File(dir, "r.jpg").also { it.writeBytes(bytes) }
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val a = repo.saveMember(Member(groupId = g, name = "A", createdAt = now))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            currencyCode = "USD",
            category = Category.FOOD,
            receiptUri = src.toURI().toString(),
            dateEpoch = now,
            createdAt = now),
        listOf(a),
        SplitInput(SplitType.EQUAL))
    val json = Exporter(repo, null, dir).backup()
    db.groups().delete(g)

    val dir2 = File(ctx.cacheDir, "rloss_ok2_${System.nanoTime()}").also { it.mkdirs() }
    files += dir2
    val res = Exporter(repo, null, dir2).restore(json)

    assertThat(res.skipped).isEmpty()
    val gid = repo.allGroups().first().single().id
    val uri = repo.expenses(gid).first().single().receiptUri
    assertThat(uri).isNotNull()
    assertThat(File(java.net.URI(uri!!)).readBytes()).isEqualTo(bytes)
  }

  // A backup taken on a phone that still holds the image carries the bytes, so a restore
  // onto another phone has something to write.
  @Test
  fun `receipt bytes carried in the backup are written out to the new install`() = runTest {
    val dir = File(ctx.cacheDir, "rloss_bytes_${System.nanoTime()}").also { it.mkdirs() }
    files += dir
    val bytes = ByteArray(256) { it.toByte() }
    val src = File(dir, "orig.jpg").also { it.writeBytes(bytes) }
    val (g, members) = repo.newGroup(now, "A")
    val a = members.single()
    repo.charge(g, a, 100, listOf(a), now, receiptUri = src.toURI().toString())

    val json = Exporter(repo, null, dir).backup()
    db.groups().delete(g)
    val dir2 = File(ctx.cacheDir, "rloss_bytes2_${System.nanoTime()}").also { it.mkdirs() }
    files += dir2
    Exporter(repo, null, dir2).restore(json)

    val gid = repo.allGroups().first().single().id
    val uri = repo.expenses(gid).first().single().receiptUri!!
    assertThat(File(java.net.URI(uri)).readBytes()).isEqualTo(bytes)
  }

  // A `file:` uri written by a build old enough to emit one bare is a path the reader has
  // to strip by hand. `URI` throws on the malformed form, and the throwback has to land in
  // a `null` rather than take the restore down with it.
  @Test
  fun `a legacy unencoded file uri still resolves`() = runTest {
    val dir = File(ctx.cacheDir, "rloss_legacy_${System.nanoTime()}").also { it.mkdirs() }
    files += dir
    val bytes = ByteArray(64) { (it * 3).toByte() }
    val src = File(dir, "legacy.jpg").also { it.writeBytes(bytes) }
    val (g, members) = repo.newGroup(now, "A", name = "L")
    val a = members.single()
    repo.charge(g, a, 100, listOf(a), now, receiptUri = "file://${src.absolutePath}")

    val json = Exporter(repo, null, dir).backup()
    db.groups().delete(g)
    val dir2 = File(ctx.cacheDir, "rloss_legacy2_${System.nanoTime()}").also { it.mkdirs() }
    files += dir2
    Exporter(repo, null, dir2).restore(json)

    val gid = repo.allGroups().first().single().id
    val restored = repo.expenses(gid).first().single().receiptUri
    assertThat(restored).isNotNull()
    assertThat(File(java.net.URI(restored!!)).readBytes()).isEqualTo(bytes)
  }
}
