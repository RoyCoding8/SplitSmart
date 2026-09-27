package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What a restore has to carry that the ledger alone does not: the roster shapes a group
// can be in, the recurring template, the archive flag and the CSV the export writes. These
// are the rows a user cannot re-derive after a restore.
@RunWith(RobolectricTestRunner::class)
class RestoreCoverageTest : DbTest() {
  private val exp: Exporter by lazy { Exporter(repo) }

  @Test
  fun `restore brings back templates`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val a = members.first()
    repo.saveTemplate(
        RecurringTemplate(
            groupId = g,
            payerId = a,
            amountMinor = 500,
            category = Category.RENT,
            splitRule = SplitRepository.encodeRule(SplitType.EQUAL, mapOf(), mapOf()),
            frequency = Frequency.WEEKLY,
            nextDueEpoch = now))
    val json = exp.backup()
    db.groups().delete(g)
    exp.restore(json)

    val gid = repo.allGroups().first().single().id
    val t = repo.templates(gid).first().single()
    assertThat(t.amountMinor).isEqualTo(500L)
    assertThat(t.frequency).isEqualTo(Frequency.WEEKLY)
    assertThat(t.nextDueEpoch).isEqualTo(now)
    assertThat(repo.members(gid).first().single { it.id == t.payerId }.name).isEqualTo("A")
  }

  @Test
  fun `duplicate member names stay distinct on restore`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val first = repo.saveMember(Member(groupId = g, name = "Alex", createdAt = now))
    repo.saveMember(Member(groupId = g, name = "Alex", createdAt = now + 1))
    repo.saveSubgroup(g, "sg", listOf(first))
    val json = exp.backup()
    db.groups().delete(g)
    exp.restore(json)

    val gid = repo.allGroups().first().single().id
    val ms = repo.members(gid).first().sortedBy { it.id }
    assertThat(ms.map { it.name }).containsExactly("Alex", "Alex (2)").inOrder()
    assertThat(repo.subgroupMembers(repo.subgroups(gid).first().single().id))
        .containsExactly(ms.first().id)
  }

  @Test
  fun `archived groups survive backup restore`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 100, listOf(a, b), now)
    repo.saveGroup(
        Group(id = g, name = "T", currencyCode = "USD", createdAt = now, archived = true))
    val json = exp.backup()
    db.groups().delete(g)
    exp.restore(json)

    val restored = repo.allGroups().first().single()
    assertThat(restored.archived).isTrue()
    // Real conservation check, not a vacuous one: the group has one expense split between
    // two members, so a non-empty nets map summing to zero means the restore preserved the
    // split rather than dropping a share.
    assertThat(repo.nets(restored.id).first()).isNotEmpty()
    assertThat(repo.nets(restored.id).first().values.sum()).isEqualTo(0L)
  }

  @Test
  fun `settlement csv keeps one payer cell`() = runTest {
    val (g, members) = repo.newGroup(now, "Ann", "Bo")
    val (a, b) = members
    repo.charge(g, a, 10000, listOf(a, b), now)
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 1000, dateEpoch = now))

    val line = exp.csv(exp.dumpGroup(g)).lines().single { it.startsWith("settlement,") }
    assertThat(line).isEqualTo("settlement,\"Bo -> Ann\",1000,,,\"\",$now,")
  }

  @Test
  fun `csv details escape separators and carry payments`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val a = repo.saveMember(Member(groupId = g, name = "a;b", createdAt = now))
    val b = repo.saveMember(Member(groupId = g, name = "B", createdAt = now))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 100,
            currencyCode = "USD",
            category = Category.FOOD,
            dateEpoch = now,
            createdAt = now),
        listOf(a, b),
        SplitInput(SplitType.EQUAL),
        mapOf(a to 60L, b to 40L),
        listOf(ExpenseItem(expenseId = 0, label = "x=y", amountMinor = 100)),
    )
    // Substring matching cannot tell an escaped value from the same characters appearing in
    // a neighbouring column, so re-import the row and check the values that come back. If
    // the `;` or `=` in a name were not escaped, the details field would split into extra
    // shares and these numbers would not hold.
    val all = exp.csv(exp.dumpGroup(g)).lines().filter { it.isNotBlank() }
    val line = all.single { it.startsWith("expense,") }
    assertThat(line).contains("item:x\\=y=100")
    val original = repo.allGroups().first().single().id
    // The whole document, header included: importCsv rejects a bare data line, and
    // feeding it one would test the header check rather than the escaping.
    assertThat(exp.importCsv("Reimport", "USD", all.joinToString("\n") + "\n").imported)
        .isEqualTo(1)
    val imported = repo.allGroups().first().single { it.id != original }
    val reimported = repo.expenses(imported.id).first().single()
    assertThat(repo.items(reimported.id).single().label).isEqualTo("x=y")
    assertThat(db.expenses().shares(reimported.id).sumOf { it.owedMinor }).isEqualTo(100L)
    // The `;` and `=` are escaped on the way out (the details field is `;`- and
    // `=`-separated, and the payer rides along in there too), so a round trip that kept
    // the backslashes would mean the import never unescaped.
    assertThat(repo.members(imported.id).first().map { it.name }).containsExactly("a;b", "B")
  }
}
