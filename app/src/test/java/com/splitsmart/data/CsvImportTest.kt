package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CsvImportTest : DbTest() {
  private val exp: Exporter by lazy { Exporter(repo) }

  @Test
  fun `csv round trip preserves ledger through tricky names`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val a = repo.saveMember(Member(groupId = g, name = "Al; \"Bo\", Jr", createdAt = now))
    val b = repo.saveMember(Member(groupId = g, name = "Zed = paid:staff", createdAt = now))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 9000,
            currencyCode = "USD",
            category = Category.FOOD,
            note = "dinner, \"quoted\"; yum",
            dateEpoch = now,
            createdAt = now),
        listOf(a, b),
        SplitInput(SplitType.EQUAL),
        mapOf(a to 6000L, b to 3000L),
        listOf(ExpenseItem(expenseId = 0, label = "pizza; half", amountMinor = 9000)),
    )
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = b,
            amountMinor = 500,
            currencyCode = "USD",
            category = Category.OTHER,
            note = null,
            dateEpoch = now,
            createdAt = now),
        listOf(a, b),
        SplitInput(SplitType.EQUAL),
    )
    repo.recordSettlement(
        Settlement(
            groupId = g,
            fromId = b,
            toId = a,
            amountMinor = 1000,
            dateEpoch = now,
            note = "thanks; a lot"))
    val before =
        repo.nets(g).first().mapKeys { (id, _) ->
          repo.members(g).first().single { it.id == id }.name
        }
    val csv = exp.csv(exp.dumpGroup(g))
    db.groups().delete(g)
    assertThat(exp.importCsv("Copy", "USD", csv).imported).isEqualTo(3)
    val gid = repo.groups().first().single().id
    val after =
        repo.nets(gid).first().mapKeys { (id, _) ->
          repo.members(gid).first().single { it.id == id }.name
        }
    assertThat(after).containsExactlyEntriesIn(before)
    val es = repo.expenses(gid).first()
    assertThat(es).hasSize(2)
    assertThat(es.single { it.note?.startsWith("dinner") == true }.note)
        .isEqualTo("dinner, \"quoted\"; yum")
    assertThat(repo.items(es.single { it.amountMinor == 9000L }.id).single().label)
        .isEqualTo("pizza; half")
    assertThat(repo.settlements(gid).first().single().note).isEqualTo("thanks; a lot")
    assertThat(repo.members(gid).first().map { it.name })
        .containsExactly("Al; \"Bo\", Jr", "Zed = paid:staff")
  }

  @Test
  fun `garbage input fails before creating a group`() = runTest {
    assertThat(runCatching { exp.importCsv("X", "USD", "hello,world\n1,2") }.isFailure).isTrue()
    assertThat(repo.allGroups().first()).isEmpty()
  }

  @Test
  fun `foreign currency rows are skipped and named`() = runTest {
    val csv =
        "type,payer,amount_minor,currency,category,note,date,details\nexpense,\"A\",1000,USD,FOOD,\"ok\",$now,\"A=1000\"\nexpense,\"A\",2000,EUR,FOOD,\"skip me\",$now,\"A=2000\"\n"
    val r = exp.importCsv("Mixed", "USD", csv)
    assertThat(r.imported).isEqualTo(1)
    // The skip has to name itself. Reporting only the count left "Imported 1" equally
    // consistent with a 1-row file and a 2-row file with a row quietly discarded, so
    // the user had no way to learn a row of their file went missing.
    assertThat(r.skipped).hasSize(1)
    assertThat(r.skipped.single()).contains("currency mismatch")
    val gid = repo.groups().first().single().id
    assertThat(repo.expenses(gid).first()).hasSize(1)
  }

  // A date the file cannot express is a malformed row, exactly as a non-numeric amount is
  // one. The date column must reject rather than substitute "now", or the same hand-edited
  // file both imports and rejects depending on which column you broke.
  @Test
  fun `a malformed date is reported as a skipped row, not dated today`() = runTest {
    val csv =
        "type,payer,amount_minor,currency,category,note,date,details\nexpense,\"A\",1000,USD,FOOD,\"good\",$now,\"A=1000\"\nexpense,\"A\",2000,USD,FOOD,\"bad date\",\"not-a-date\",\"A=2000\"\n"
    val r = exp.importCsv("Dates", "USD", csv)
    assertThat(r.imported).isEqualTo(1)
    assertThat(r.skipped).hasSize(1)
    assertThat(r.skipped.single()).contains("not-a-date")
    val gid = repo.groups().first().single().id
    val e = repo.expenses(gid).first().single()
    // The one that did import kept its real date, so the count is not "one fewer try".
    assertThat(e.note).isEqualTo("good")
    assertThat(e.dateEpoch).isEqualTo(now)
  }

  @Test
  fun `a malformed settlement date is reported as a skipped row`() = runTest {
    val csv =
        "type,payer,amount_minor,currency,category,note,date,details\nexpense,\"A\",1000,USD,FOOD,\"good\",$now,\"A=1000\"\nsettlement,\"A -> B\",500,,,\"paid\",\"nope\",\n"
    val r = exp.importCsv("Dates", "USD", csv)
    // The settlement row is the one that has to go: accepting it would date a payment from
    // March to the day the import ran, which is the only record of when the money moved.
    assertThat(r.imported).isEqualTo(1)
    assertThat(r.skipped).hasSize(1)
    val gid = repo.groups().first().single().id
    assertThat(repo.settlements(gid).first()).isEmpty()
  }

  // Every row the writer produced has to be consumed by the reader or reported as dropped.
  // A `subgroups` line with no branch in the import loop is a bare count, not in `imported`,
  // not named in `skipped`, and a clean "Imported N" over a file with a line of it
  // discarded. The round trip is what pins it, so the assertions are on what came back.
  @Test
  fun `subgroup rows survive the csv round trip`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.saveSubgroup(g, "roomies", listOf(a, b))
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = a,
            amountMinor = 1000,
            currencyCode = "USD",
            category = Category.FOOD,
            dateEpoch = now,
            createdAt = now),
        listOf(a, b),
        SplitInput(SplitType.EXACT, mapOf(a to 600L, b to 400L)))
    repo.recordSettlement(
        Settlement(groupId = g, fromId = b, toId = a, amountMinor = 100, dateEpoch = now))
    // A subgroup is not money, so it is not counted in `imported`; the round trip is what
    // pins that it is at least rebuilt rather than dropped in silence.
    val text = exp.csv(exp.dumpGroup(g))
    db.groups().delete(g)
    val r = exp.importCsv("Copy", "USD", text)

    assertThat(r.skipped).isEmpty()
    val gid = repo.groups().first().single().id
    assertThat(repo.expenses(gid).first()).hasSize(1)
    assertThat(repo.settlements(gid).first()).hasSize(1)
    val sg = repo.subgroups(gid).first().single()
    assertThat(sg.name).isEqualTo("roomies")
    val names = repo.members(gid).first().associate { it.id to it.name }
    assertThat(repo.subgroupMembers(sg.id).map { names[it] }).containsExactly("A", "B")
  }

  // The writer has to state who "you" are. Guessing "the first payer in the file" is wrong
  // whenever you are not the first payer, and the writer has the real answer.
  @Test
  fun `a csv export states who you are and the import believes it`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.saveGroup(
        Group(id = g, name = "T", currencyCode = "USD", selfMemberId = b, createdAt = now))
    repo.charge(g, a, 1000, listOf(a, b), now)
    val text = exp.csv(exp.dumpGroup(g))
    // A is the only payer in the file, so the old fallback would have made you A.
    // The file has to say B, or this assertion is vacuous.
    assertThat(text.lines().first { it.startsWith("self,") }).isEqualTo("self,\"B\",,,,,,")

    db.groups().delete(g)
    val r = exp.importCsv("Copy", "USD", text)
    assertThat(r.skipped).isEmpty()
    val ig = repo.allGroups().first().single()
    val self = ig.selfMemberId
    assertThat(self).isNotNull()
    assertThat(repo.members(ig.id).first().single { it.id == self }.name).isEqualTo("B")
  }
}
