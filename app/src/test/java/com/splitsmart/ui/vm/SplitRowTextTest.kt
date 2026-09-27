package com.splitsmart.ui.vm

import com.google.common.truth.Truth.assertThat
import com.splitsmart.core.splits.percent
import com.splitsmart.core.splits.shares
import com.splitsmart.data.SplitType
import org.junit.jupiter.api.Test

// Reopening a saved expense puts the split back into the editor, and the editor only
// accepts the form it parses. The owed amounts do not record what the user typed, so the
// type has to be stored and the text re-derived from it.
class SplitRowTextTest {

  @Test
  fun `an equal split carries no per-row text`() {
    assertThat(splitRowTexts(keyed(3000L, 3000L, 3000L), 9000L, SplitType.EQUAL))
        .containsExactly(0L, "", 1L, "", 2L, "")
  }

  @Test
  fun `an exact split is its own amount`() {
    assertThat(splitRowTexts(keyed(3000L, 6000L), 9000L, SplitType.EXACT))
        .containsExactly(0L, "30.00", 1L, "60.00")
  }

  // What this renders must parse back to the same owed amounts, or saving an edit
  // silently changes the split.
  @Test
  fun `a percent split parses back to the same shares`() {
    // 60/20/20 of a 90.00 bill.
    val total = 9000L
    val texts = splitRowTexts(keyed(5400L, 1800L, 1800L), total, SplitType.PERCENT)
    assertThat(texts.values).containsExactly("60.00", "20.00", "20.00").inOrder()

    val parsed = parseInput(total, texts.map { (i, s) -> SplitRow(i, "m", true, s) }, SplitType.PERCENT)
    assertThat(parsed.valid).isTrue()
    assertThat(parsed.input.bps).containsExactly(0L, 6000, 1L, 2000, 2L, 2000).inOrder()
  }

  // 3333 three times of 9999 is 9999bps rather than 10000, so the last basis point
  // has to land somewhere. Rows are tied on remainder, and the tie goes to the lowest
  // member id: 33.34, 33.33, 33.33.
  @Test
  fun `percentages that do not come to a hundred are still shown as entered`() {
    val texts = splitRowTexts(keyed(3333L, 3333L, 3333L), 9999L, SplitType.PERCENT)
    assertThat(texts).containsExactly(0L, "33.34", 1L, "33.33", 2L, "33.33")
  }

  // Weights are only recoverable up to a common factor, so the ratio is reduced.
  @Test
  fun `a shares split comes back as a whole weight ratio`() {
    val total = 9000L
    val texts = splitRowTexts(keyed(3000L, 6000L), total, SplitType.SHARES)
    assertThat(texts.values).containsExactly("1", "2").inOrder()

    val parsed =
        parseInput(total, texts.map { (i, s) -> SplitRow(i, "m", true, s) }, SplitType.SHARES)
    assertThat(parsed.valid).isTrue()
    // 1:2 of 90.00 is 30.00 and 60.00 whichever scale the weights come back at.
    assertThat(shares(total, parsed.input.values)).containsExactly(0L, 3000L, 1L, 6000L)
  }

  @Test
  fun `a share of nothing is a weight of zero`() {
    // shares() accepts a zero weight, and that is what reproduces a member owing
    // nothing. The reduction has no answer for a zero row, so refusing it made every
    // row come back blank.
    assertThat(splitRowTexts(keyed(3000L, 0L), 3000L, SplitType.SHARES))
        .containsExactly(0L, "1", 1L, "0")
        .inOrder()
  }

  @Test
  fun `a shares split with a floored-to-zero share reopens and saves`() {
    // A member floors to zero whenever total * weight is under the denominator, so
    // any weight under 1% of a small bill lands there. The ledger accepts that state,
    // so it is a stored expense that has to be editable.
    val total = 2L
    val owed = keyed(0L, 1L, 1L)
    val texts = splitRowTexts(owed, total, SplitType.SHARES)
    val parsed =
        parseInput(total, texts.map { (i, s) -> SplitRow(i, "m", true, s) }, SplitType.SHARES)
    assertThat(parsed.valid).isTrue()
    assertThat(shares(total, parsed.input.values)).isEqualTo(owed)
  }

  @Test
  fun `a shares split with a zero share rejects a negative weight`() {
    // The other direction stays refused: owed only ever moves toward a member.
    val rows =
        listOf(SplitRow(0L, "a", true, "0"), SplitRow(1L, "b", true, "-1"))
    assertThat(parseInput(100L, rows, SplitType.SHARES).valid).isFalse()
  }

  // ---------------------------------------------------------------- round trip

  private fun reopen(total: Long, owed: List<Long>, type: SplitType): Parsed {
    val texts = splitRowTexts(keyed(*owed.toLongArray()), total, type)
    return parseInput(total, texts.map { (i, s) -> SplitRow(i, "m", true, s) }, type)
  }

  private fun keyed(vararg owed: Long) = owed.mapIndexed { i, v -> i.toLong() to v }.toMap()

  // Basis points are coarser than minor units -- 10000 of them against the bill's own
  // total -- so re-deriving them from the amounts is lossy. What it cannot be is a
  // split that fails to save.
  private fun withinACent(saved: Map<Long, Long>, owed: List<Long>) {
    assertThat(saved.keys).containsExactlyElementsIn(owed.indices.map { it.toLong() })
    saved.forEach { (id, amount) ->
      assertThat(Math.abs(amount - owed[id.toInt()])).isAtMost(1L)
    }
  }

  // 1:2:3 of 1000 apportions to 166, 333 and 501, which share no divisor, so each row
  // has to be rendered against the total rather than reduced per row.
  @Test
  fun `a one to two to three ratio survives a reopen`() {
    val total = 1000L
    val owed = listOf(166L, 333L, 501L)
    val parsed = reopen(total, owed, SplitType.SHARES)

    assertThat(parsed.valid).isTrue()
    assertThat(shares(total, parsed.input.values)).isEqualTo(keyed(166L, 333L, 501L))
  }

  // 2:3:4 of 1000 is 223, 333 and 444. Rendering each row against its own divisor with
  // the total gives 223, 333 and 111 -- a ratio that saves as 334, 500 and 166.
  @Test
  fun `a two to three to four ratio survives a reopen`() {
    val total = 1000L
    val owed = listOf(223L, 333L, 444L)
    val parsed = reopen(total, owed, SplitType.SHARES)

    assertThat(parsed.valid).isTrue()
    assertThat(shares(total, parsed.input.values)).isEqualTo(keyed(223L, 333L, 444L))
  }

  // 600 is six times 100, so 1:2:3 of it is exactly 100, 200 and 300 -- and against the
  // total each of those divides to a weight of 1, which would have re-split a three-way
  // 1:2:3 as 200 each.
  @Test
  fun `weights come back in proportion when the amounts divide evenly`() {
    val parsed = reopen(600L, listOf(100L, 200L, 300L), SplitType.SHARES)

    assertThat(parsed.input.values.values.toList()).containsExactly(1L, 2L, 3L).inOrder()
  }

  /** The equal case: three people splitting 1000, which is not a multiple of 3. */
  @Test
  fun `an equal ratio survives a reopen`() {
    val total = 1000L
    val owed = listOf(334L, 333L, 333L)
    val parsed = reopen(total, owed, SplitType.SHARES)

    assertThat(parsed.valid).isTrue()
    assertThat(shares(total, parsed.input.values)).isEqualTo(keyed(334L, 333L, 333L))
  }

  // Reopening twice has to be a fixed point, or the split walks further from the truth
  // on every edit.
  @Test
  fun `reopening a shares split twice changes nothing`() {
    val total = 1000L
    val first = shares(total, keyed(1L, 2L, 3L))
    val once = reopen(total, first.values.toList(), SplitType.SHARES)
    val twice = reopen(total, shares(total, once.input.values).values.toList(), SplitType.SHARES)

    // 167/333/500 is what shares(1000, 1:2:3) produces, and it shares no divisor, so
    // the weights it comes back as are the amounts themselves.
    assertThat(once.input.values.values.toList()).isEqualTo(listOf(167L, 333L, 500L))
    assertThat(shares(total, twice.input.values)).isEqualTo(first)
  }

  // 1:1:2:3 of 1000 is 143, 142, 286 and 429; 143 and 286 share a divisor with 1000
  // that 142 and 429 do not, so a per-row divisor comes out four different ways.
  @Test
  fun `a four way ratio survives a reopen`() {
    val total = 1000L
    val owed = listOf(143L, 142L, 286L, 429L)
    val parsed = reopen(total, owed, SplitType.SHARES)

    assertThat(parsed.valid).isTrue()
    assertThat(shares(total, parsed.input.values))
        .isEqualTo(keyed(143L, 142L, 286L, 429L))
  }

  // ------------------------------------------------------------------ percent

  // 400/1200 is one third, and 33.33% three times is 9999bps, not 10000. The editor
  // refuses a percent split that does not come to a hundred, so a correctly stored
  // expense reopened for a note edit would have had Save disabled.
  @Test
  fun `a third each comes back as percentages that add to a hundred`() {
    val total = 1200L
    val owed = listOf(400L, 400L, 400L)
    val parsed = reopen(total, owed, SplitType.PERCENT)

    assertThat(parsed.valid).isTrue()
    assertThat(parsed.input.bps.values.sum()).isEqualTo(10000)
    withinACent(percent(total, parsed.input.bps), owed)
  }

  // 1250, 1250, 1250 and 1249 of 4999 each round to a basis point count that comes
  // to 10001.
  @Test
  fun `a four way percent split comes back adding to a hundred`() {
    val total = 4999L
    val owed = listOf(1250L, 1250L, 1250L, 1249L)
    val parsed = reopen(total, owed, SplitType.PERCENT)

    assertThat(parsed.valid).isTrue()
    assertThat(parsed.input.bps.values.sum()).isEqualTo(10000)
    withinACent(percent(total, parsed.input.bps), owed)
  }

  // The 10000 basis points are spent by largestRemainder, which breaks a tie on member
  // id, so the extra basis point lands on the lowest id of three tied rows.
  @Test
  fun `a third each spends the leftover on one row and still adds to a hundred`() {
    val parsed = reopen(1200L, listOf(400L, 400L, 400L), SplitType.PERCENT)

    assertThat(parsed.input.bps.values.toList()).containsExactly(3334, 3333, 3333).inOrder()
  }

  // 3333 three times is 9999, so the last basis point of the bill has to be given to
  // someone, and the 33.34 it lands on is the odd one out of three identical rows.
  @Test
  fun `a third each of 9999 comes back adding to a hundred`() {
    val total = 9999L
    val owed = listOf(3333L, 3333L, 3333L)
    val parsed = reopen(total, owed, SplitType.PERCENT)

    assertThat(parsed.valid).isTrue()
    assertThat(parsed.input.bps.values.sum()).isEqualTo(10000)
    withinACent(percent(total, parsed.input.bps), owed)
  }

  /** 666, 666 and 667 of 1999, which rounds per row to 10001bps. */
  @Test
  fun `an uneven third comes back adding to a hundred`() {
    val total = 1999L
    val owed = listOf(666L, 666L, 667L)
    val parsed = reopen(total, owed, SplitType.PERCENT)

    assertThat(parsed.valid).isTrue()
    assertThat(parsed.input.bps.values.sum()).isEqualTo(10000)
    withinACent(percent(total, parsed.input.bps), owed)
  }

  /** 60/20/20 of 90.00 is the case that was already right, and has to stay right. */
  @Test
  fun `an exact percent split does not regress`() {
    val total = 9000L
    val owed = listOf(5400L, 1800L, 1800L)
    val parsed = reopen(total, owed, SplitType.PERCENT)

    assertThat(parsed.input.bps.values.toList()).containsExactly(6000, 2000, 2000).inOrder()
    assertThat(percent(total, parsed.input.bps)).isEqualTo(keyed(5400L, 1800L, 1800L))
  }

  // A split is only defined across its whole set, so a total that divides by neither
  // 3 nor 6 walks through both types and checks each survives its own reopen.
  @Test
  fun `a group split both ways survives both reopens`() {
    val total = 4999L

    val percentSplit = keyed(1250L, 1250L, 1250L, 1249L)
    val percentAgain = reopen(total, percentSplit.values.toList(), SplitType.PERCENT)
    assertThat(percentAgain.valid).isTrue()
    withinACent(percent(total, percentAgain.input.bps), percentSplit.values.toList())

    val weightSplit = shares(total, keyed(1L, 2L, 3L, 4L))
    val sharesAgain = reopen(total, weightSplit.values.toList(), SplitType.SHARES)
    assertThat(sharesAgain.valid).isTrue()
    assertThat(shares(total, sharesAgain.input.values)).isEqualTo(weightSplit)
  }

  // The text is keyed by member id rather than by position, because the editor writes
  // it onto rows built from the group's member list, a different order from the map the
  // shares came back in.
  @Test
  fun `text is keyed by member id not by position`() {
    val byMember = mapOf(70L to 1200L, 10L to 2400L, 40L to 3600L)

    assertThat(splitRowTexts(byMember, 7200L, SplitType.SHARES))
        .containsExactly(70L, "1", 10L, "2", 40L, "3")
    assertThat(splitRowTexts(byMember, 7200L, SplitType.EXACT)[40L]).isEqualTo("36.00")
  }

  // A group that is not fully participating is still a split over the people who are.
  @Test
  fun `only the participating members are converted`() {
    val participants = mapOf(10L to 3333L, 20L to 3333L, 30L to 3333L)

    assertThat(splitRowTexts(participants, 9999L, SplitType.PERCENT)).hasSize(3)
    assertThat(splitRowTexts(participants, 9999L, SplitType.SHARES)).hasSize(3)
  }

  // -------------------------------------------------------------------- exact

  // Routing the amount through a Double starts losing cents past 2^53 minor units, which
  // is about 90 trillion dollars, and the loss is silent.
  @Test
  fun `an exact row keeps every cent of an amount past the double's reach`() {
    val owed = 9007199254740993L
    val text = splitRowTexts(keyed(owed), owed, SplitType.EXACT)[0L]!!

    assertThat(majorToMinor(text)).isEqualTo(owed)
  }
}
