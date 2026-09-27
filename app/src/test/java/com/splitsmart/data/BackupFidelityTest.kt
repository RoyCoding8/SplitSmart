package com.splitsmart.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A backup is only worth having if it restores the group you backed up. These cover the
// data that has to cross the file: a rate table, a comment thread, a receipt, a recurring
// template, a self identity. The one failure mode a user cannot detect is the restore
// reporting success with the data simply gone.
@RunWith(RobolectricTestRunner::class)
class BackupFidelityTest : DbTest() {
  private val exp: Exporter by lazy { Exporter(repo) }

  /** The one group a restore should have added, found by whatever it is not. */
  private suspend fun restoredGroupId(original: Long): Long =
      repo.allGroups().first().single { it.id != original }.id

  @Test
  fun `an imported group knows which member is you`() = runTest {
    val (g, members) = repo.newGroup(now, "Me", "You", name = "Trip")
    val (me, you) = members
    repo.saveGroup(
        Group(id = g, name = "Trip", currencyCode = "USD", selfMemberId = me, createdAt = now))
    repo.charge(g, me, 1000, listOf(me, you), now)

    val r = exp.importCsv("Trip", "USD", exp.csv(exp.dumpGroup(g)))
    assertThat(r.imported).isEqualTo(1)
    assertThat(r.skipped).isEmpty()

    val ig = repo.allGroups().first().single { it.id != g }
    assertThat(ig.selfMemberId).isNotNull()
    // me paid 1000 shared with you, so the group owes me 500 and "you" is now the
    // debtor. A null selfMemberId would have read this as a hardcoded 0.
    assertThat(repo.nets(ig.id).first()[ig.selfMemberId!!]).isEqualTo(500L)
  }

  @Test
  fun `a restore brings back exchange rates comments and the activity feed`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.saveGroup(
        Group(id = g, name = "T", currencyCode = "USD", selfMemberId = a, createdAt = now))
    repo.saveRate(
        FxRate(groupId = g, fromCode = "EUR", toCode = "USD", rate = 1.1, timeEpoch = now))
    repo.charge(g, a, 1000, listOf(a, b), now)
    val eid = repo.expenses(g).first().single().id
    repo.addComment(eid, a, "was this the pasta place?")
    repo.addComment(eid, b, "yes")

    val res = exp.restore(exp.backup())
    assertThat(res.skipped).isEmpty()

    val rg = restoredGroupId(g)
    assertThat(repo.rates(rg).first().map { it.rate }).containsExactly(1.1)
    val re = repo.expenses(rg).first().single()
    assertThat(repo.comments(re.id).first().map { it.text })
        .containsExactly("was this the pasta place?", "yes")
    assertThat(repo.events(rg).first()).isNotEmpty()
  }

  // A foreign-currency expense with no rate falls back to parity, so a backup that
  // quietly lost its rate table restores a group that balances to a plausible but wrong
  // number. The rates have to round-trip rather than be treated as cache.
  @Test
  fun `a restored foreign currency expense still converts at the saved rate`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.saveRate(
        FxRate(groupId = g, fromCode = "EUR", toCode = "USD", rate = 2.0, timeEpoch = now))
    repo.charge(g, a, 1000, listOf(a, b), now, currencyCode = "EUR")
    val before = repo.nets(g).first().values.toList()

    exp.restore(exp.backup())
    val rg = restoredGroupId(g)
    // Compare by value, not by member id -- the restored group has freshly assigned ids.
    assertThat(repo.nets(rg).first().values.toList()).isEqualTo(before)
  }

  // A dump lists settlements newest first, because that is what the feed and the history
  // screen want. But `recordSettlement` refuses a payment from someone no longer in the
  // red, and that check is on the balance as it stands at the instant of the write, so
  // replaying backwards drops the earlier instalments into the skipped list while the
  // restore still reports success.
  //
  // B owes A 2000. Paying it in three instalments leaves 0 owed; replayed newest first
  // the last goes in first and the earlier two are refused, leaving the group 200 out.
  @Test
  fun `a restore replays instalments in the order they were paid`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.saveGroup(
        Group(id = g, name = "T", currencyCode = "USD", selfMemberId = a, createdAt = now))
    repo.charge(g, a, 4000, listOf(a, b), now)
    // Three payments, and the largest is the last one.
    listOf(50L to now, 150L to now + 50_000, 1800L to now + 90_000).forEach { (amt, at) ->
      repo.recordSettlement(
          Settlement(groupId = g, fromId = b, toId = a, amountMinor = amt, dateEpoch = at))
    }
    assertThat(repo.nets(g).first()[a]).isEqualTo(0L)

    val res = exp.restore(exp.backup())
    assertThat(res.skipped).isEmpty()

    val rg = restoredGroupId(g)
    assertThat(repo.settlements(rg).first()).hasSize(3)
    // By value, not by member id: the restored group has freshly assigned ids. All
    // three payments applied means both balances land on zero, rather than the 200
    // that the two dropped instalments would leave behind.
    assertThat(repo.nets(rg).first().values.toList()).containsExactly(0L, 0L)
  }

  // The dump is name-keyed all the way down, so the writer has to say which "Alex" is
  // which before it writes either. Keyed by name, the second overwrites the first in the
  // share map and the shares stop summing to the amount, so the restore refuses the
  // expense with a "shares must sum to total" that names the symptom, not the cause.
  // The members do survive as two rows, which is what makes it read as a rounding bug.
  @Test
  fun `two members with the same name keep their own shares`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val alex1 = repo.saveMember(Member(groupId = g, name = "Alex", createdAt = now))
    val alex2 = repo.saveMember(Member(groupId = g, name = "Alex", createdAt = now + 1))
    repo.saveMember(Member(groupId = g, name = "Bo", createdAt = now + 2))
    val bo = repo.members(g).first().single { it.name == "Bo" }.id
    // 900 split three ways as 400/300/200. The two Alexes are deliberately unequal, so
    // a collapse shows up as a wrong number rather than as no number. All three shares
    // are stated: an EXACT split whose parts do not sum to the bill is rejected outright.
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = alex1,
            amountMinor = 900,
            currencyCode = "USD",
            category = Category.FOOD,
            note = "two alexes",
            dateEpoch = now,
            createdAt = now),
        listOf(alex1, alex2, bo),
        SplitInput(SplitType.EXACT, mapOf(alex1 to 400L, alex2 to 300L, bo to 200L)))

    val json = exp.backup()
    db.groups().delete(g)
    val res = exp.restore(json)

    // The whole point: not reported, because nothing was lost.
    assertThat(res.skipped).isEmpty()
    assertThat(res.restored).isEqualTo(1)
    val rg = restoredGroupId(g)
    val e = repo.expenses(rg).first().single()
    // The conservation check the collapse actually broke.
    assertThat(repo.shares(e.id).sumOf { it.owedMinor }).isEqualTo(900L)
    val byName =
        repo.members(rg).first().associate { it.id to it.name }.let { names ->
          repo.shares(e.id).associate { s -> names[s.memberId] to s.owedMinor }
        }
    assertThat(byName).containsExactly("Alex", 400L, "Alex (2)", 300L, "Bo", 200L)
  }

  /** The payer, the settlement endpoints and the comment author are all name slots. */
  @Test
  fun `a settlement between two same-named members keeps both ends`() = runTest {
    val g = repo.saveGroup(Group(name = "T", currencyCode = "USD", createdAt = now))
    val alex1 = repo.saveMember(Member(groupId = g, name = "Alex", createdAt = now))
    val alex2 = repo.saveMember(Member(groupId = g, name = "Alex", createdAt = now + 1))
    repo.saveMember(Member(groupId = g, name = "Bo", createdAt = now + 2))
    val bo = repo.members(g).first().single { it.name == "Bo" }.id
    repo.saveExpense(
        Expense(
            groupId = g,
            payerId = bo,
            amountMinor = 900,
            currencyCode = "USD",
            category = Category.FOOD,
            dateEpoch = now,
            createdAt = now),
        listOf(bo, alex1, alex2),
        SplitInput(SplitType.EXACT, mapOf(alex1 to 300L, alex2 to 300L, bo to 300L)))
    // Each Alex owes 300. The payment comes from the second one, so a collapsed
    // writer would name the first as both ends of the transfer.
    repo.recordSettlement(
        Settlement(groupId = g, fromId = alex2, toId = bo, amountMinor = 100, dateEpoch = now))

    val json = exp.backup()
    db.groups().delete(g)
    val res = exp.restore(json)
    assertThat(res.skipped).isEmpty()

    val rg = restoredGroupId(g)
    val names = repo.members(rg).first().associate { it.id to it.name }
    val s = repo.settlements(rg).first().single()
    assertThat(names[s.fromId]).isEqualTo("Alex (2)")
    assertThat(names[s.toId]).isEqualTo("Bo")
  }

  // A comment thread dated in March that restores dated today is a conversation
  // rewritten to look like it happened during the restore.
  @Test
  fun `comments keep the timestamp they were written at`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.charge(g, a, 1000, listOf(a, b), now)
    val eid = repo.expenses(g).first().single().id
    // A fixed March epoch, so the assertion can be a literal rather than "roughly now".
    val march = 1_772_000_000_000L
    db.comments().insert(
        Comment(expenseId = eid, authorId = b, text = "that was the pasta place", timeEpoch = march))

    exp.restore(exp.backup())
    val rg = restoredGroupId(g)
    val c = repo.comments(repo.expenses(rg).first().single().id).first().single()
    assertThat(c.timeEpoch).isEqualTo(march)
  }

  // A receipt the exporter cannot write back is a loss, and every other loss in this
  // function is reported.
  @Test
  fun `a receipt that cannot be written back is reported`() = runTest {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val src = java.io.File(ctx.cacheDir, "rcpt_${System.nanoTime()}").apply { mkdirs() }
    val slip = java.io.File(src, "slip.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    val (g, members) = repo.newGroup(now, "A")
    val a = members.single()
    repo.charge(
        g, a, 1000, listOf(a), now, note = "has a receipt", receiptUri = slip.toURI().toString())

    // The receipt bytes are in the backup but the file URI has nowhere to be written
    // back to, which is the same null the unreadable-directory and failed-write cases hit.
    val json = exp.backup()
    db.groups().delete(g)
    val res = exp.restore(json)

    assertThat(res.restored).isEqualTo(1)
    assertThat(res.skipped).hasSize(1)
    assertThat(res.skipped.single()).contains("has a receipt")
    assertThat(repo.expenses(restoredGroupId(g)).first().single().receiptUri).isNull()
    src.deleteRecursively()
  }

  // One category name the app refuses must not take the entire backup with it: a
  // 41-character name from a foreign file that throws out of `restore()` leaves the
  // user with "Restore failed" and nothing restored at all.
  @Test
  fun `an over-long custom category does not abort the restore`() = runTest {
    val cat = repo.saveCustomCat(CustomCategory(name = "Cab", emoji = "T"))
    val (g, members) = repo.newGroup(now, "A")
    val a = members.single()
    repo.charge(g, a, 1000, listOf(a), now, customCatId = cat)
    val json = exp.backup()
    // A good one and a 41-character one, the length saveCustomCat refuses. Written
    // into the real array rather than replacing an empty one: the encoder omits a
    // default-valued collection entirely, so there is no `"customCategories":[]`
    // in a backup that happens to have no categories.
    val tooLong = "C".repeat(41)
    val tainted =
        json.replace(
            "\"customCategories\":[{\"id\":$cat,\"name\":\"Cab\",\"emoji\":\"T\"}]",
            "\"customCategories\":[" +
                "{\"id\":$cat,\"name\":\"Cab\",\"emoji\":\"T\"}," +
                "{\"id\":99,\"name\":\"$tooLong\",\"emoji\":\"X\"}]")
    assertThat(tainted).isNotEqualTo(json)
    db.groups().delete(g)

    val res = exp.restore(tainted)
    // The groups still land, which is the twenty-good-rows half of the contract.
    assertThat(res.restored).isEqualTo(1)
    assertThat(res.skipped).hasSize(1)
    assertThat(res.skipped.single()).contains("category")
    // The good one was re-created and the expense still points at it, so the loop
    // did not simply stop at the first bad row.
    val rg = restoredGroupId(g)
    assertThat(repo.expenses(rg).first().single().customCatId)
        .isEqualTo(repo.customCats().first().single { it.name == "Cab" }.id)
  }

  // The group, its members and its "you" are one unit. Written as three steps, a death
  // between them leaves a persisted group with members and no selfMemberId, which every
  // balance surface reads as a hardcoded zero. This pins the unit, not the process death.
  @Test
  fun `a restore never leaves a group without a self identity`() = runTest {
    val (g, members) = repo.newGroup(now, "A", "B")
    val (a, b) = members
    repo.saveGroup(
        Group(id = g, name = "T", currencyCode = "USD", selfMemberId = b, createdAt = now))
    repo.charge(g, a, 10000, listOf(a, b), now)
    val json = exp.backup()
    db.groups().delete(g)

    exp.restore(json)
    for (grp in repo.allGroups().first()) {
      if (repo.members(grp.id).first().isNotEmpty()) {
        assertThat(grp.selfMemberId).isNotNull()
      }
    }
    val rg = restoredGroupId(g)
    // And the identity is the right one, not merely present.
    val self = repo.group(rg).first()!!.selfMemberId
    assertThat(repo.members(rg).first().single { it.id == self }.name).isEqualTo("B")
  }
}
