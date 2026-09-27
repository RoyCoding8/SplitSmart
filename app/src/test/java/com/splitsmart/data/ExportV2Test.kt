package com.splitsmart.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportV2Test : DbTest() {
  private val exp: Exporter by lazy { Exporter(repo) }

  @Test
  fun `v2 round trip preserves multi-payer items subgroups kind receipts`() = runTest {
    val g =
        repo.saveGroup(
            Group(name = "T", currencyCode = "USD", kind = GroupKind.FRIEND, createdAt = now))
    val a = repo.saveMember(Member(groupId = g, name = "A", createdAt = now))
    val b = repo.saveMember(Member(groupId = g, name = "B", createdAt = now))
    repo.saveSubgroup(g, "roomies", listOf(a, b))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 9000,
            currencyCode = "USD",
            category = Category.FOOD,
            note = "dinner",
            dateEpoch = now,
            receiptUri = "content://r/1",
            createdAt = now),
        listOf(a, b),
        SplitInput(SplitType.EQUAL),
        mapOf(a to 6000L, b to 3000L),
        listOf(ExpenseItem(expenseId = 0, label = "pizza", amountMinor = 9000)),
    )
    val json = exp.backup()
    assertThat(json).contains("\"version\":2")
    assertThat(exp.csv(exp.dumpGroup(g))).contains("pizza")
    db.groups().delete(g)
    assertThat(exp.restore(json).restored).isEqualTo(1)
    val gid = repo.groups().first().single().id
    assertThat(repo.groups().first().single().kind).isEqualTo(GroupKind.FRIEND)
    assertThat(repo.subgroups(gid).first().single().name).isEqualTo("roomies")
    val reA = repo.members(gid).first().single { it.name == "A" }.id
    val reB = repo.members(gid).first().single { it.name == "B" }.id
    assertThat(repo.subgroupMembers(repo.subgroups(gid).first().single().id))
        .containsExactly(reA, reB)
    val e = repo.expenses(gid).first().single()
    assertThat(repo.items(e.id).single().label).isEqualTo("pizza")
    assertThat(e.receiptUri).isEqualTo("content://r/1")
    assertThat(repo.nets(gid).first()).containsExactlyEntriesIn(mapOf(reA to 1500L, reB to -1500L))
    assertThat(repo.plan(gid, true).single().amountMinor).isEqualTo(1500L)
  }

  @Test
  @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
  fun `restore preserves custom category inlines receipt bytes and keeps you`() = runTest {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val dir = java.io.File(ctx.cacheDir, "rcpt-regression").apply { deleteRecursively() }
    dir.mkdirs()
    val bytes = byteArrayOf(0x42, 0x17, 0x7F, 0x00, 0x2A)
    val receipt = java.io.File(dir, "slip.jpg").apply { writeBytes(bytes) }
    val uri = java.io.File(receipt.toURI()).toURI().toString()

    val files = java.io.File(ctx.filesDir, "export-restore").apply { deleteRecursively() }
    files.mkdirs()
    val exp2 = Exporter(repo, ctx.contentResolver, files)

    val cat = repo.saveCustomCat(CustomCategory(name = "Cab", emoji = "T"))
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.saveGroup( // "you" is B
        Group(
            id = g,
            name = "T",
            currencyCode = "USD",
            selfMemberId = b,
            createdAt = now))
    repo.charge(g, a, 1000, listOf(a, b), now, category = Category.OTHER, customCatId = cat, receiptUri = uri)

    val json = exp2.backup()
    assertThat(json).contains(kotlin.io.encoding.Base64.encode(bytes))
    assertThat(json).contains("customCategories")

    db.groups().delete(g)
    assertThat(exp2.restore(json).restored).isEqualTo(1)

    val gid = repo.groups().first().single().id
    val restored = repo.expenses(gid).first().single()

    val newCat = repo.customCats().first().single { it.name == "Cab" }
    assertThat(newCat.emoji).isEqualTo("T")
    assertThat(restored.customCatId).isEqualTo(newCat.id)

    val restoredUri = requireNotNull(restored.receiptUri)
    assertThat(restoredUri).isNotEqualTo(uri)
    val written = java.io.File(java.net.URI(restoredUri))
    assertThat(written.isFile).isTrue()
    assertThat(written.readBytes()).isEqualTo(bytes)

    val selfId = repo.group(gid).first()!!.selfMemberId
    assertThat(repo.members(gid).first().single { it.id == selfId }.name).isEqualTo("B")
  }

  @Test
  fun `v1 backup still restores`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 10000, listOf(a, b), now)
    val json = exp.backup().replaceFirst("\"version\":2", "\"version\":1")
    db.groups().delete(g)
    assertThat(exp.restore(json).restored).isEqualTo(1)
    assertThat(repo.plan(repo.groups().first().single().id, true).single().amountMinor)
        .isEqualTo(5000L)
  }

  @Test
  fun `unknown version rejected, subgroup roster carried in the csv`() = runTest {
    try {
      exp.restore("{\"version\":99,\"exportedAt\":1,\"groups\":[]}")
      assert(false) { "expected" }
    } catch (_: IllegalArgumentException) {}
    val (g, members) = repo.newGroup(now, "A")
    val a = members.single()
    repo.saveSubgroup(g, "s1", listOf(a))
    repo.charge(g, a, 100, listOf(a), now)
    val csv = exp.csv(exp.dumpGroup(g))
    assertThat(csv).contains("subgroup,\"s1\"")
    assertThat(csv).contains("\"A\"")
  }

  @Test
  fun `restoring a file the database already has reports the copy`() = runTest {
    val (g, members) = repo.newGroup(now, "A", name = "Trip")
    val a = members.single()
    repo.charge(g, a, 1000, listOf(a), now)
    val json = exp.backup()

    db.groups().delete(g)
    assertThat(exp.restore(json).duplicated).isEmpty()

    val again = exp.restore(json)
    assertThat(again.duplicated).containsExactly("Trip")
    assertThat(repo.allGroups().first()).hasSize(2)
  }
}
