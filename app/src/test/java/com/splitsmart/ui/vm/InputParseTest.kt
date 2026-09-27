package com.splitsmart.ui.vm

import com.google.common.truth.Truth.assertThat
import com.splitsmart.data.SplitType
import org.junit.jupiter.api.Test

class InputParseTest {
  private fun rows(vararg r: SplitRow) = r.toList()

  @Test
  fun `exact parses major units to cents`() {
    val out =
        parseInput(
            10000L,
            rows(SplitRow(1, "A", true, "60"), SplitRow(2, "B", true, "40")),
            SplitType.EXACT)
    assertThat(out.valid).isTrue()
    assertThat(out.input.values).containsExactly(1L, 6000L, 2L, 4000L)
  }

  @Test
  fun `percent parses decimals to bps`() {
    val out =
        parseInput(
            10000L,
            rows(
                SplitRow(1, "A", true, "33.33"),
                SplitRow(2, "B", true, "33.33"),
                SplitRow(3, "C", true, "33.34")),
            SplitType.PERCENT)
    assertThat(out.valid).isTrue()
    assertThat(out.input.bps).containsExactly(1L, 3333, 2L, 3333, 3L, 3334)
  }

  @Test
  fun `excluded rows contribute nothing`() {
    val out =
        parseInput(
            10000L,
            rows(SplitRow(1, "A", true, "100"), SplitRow(2, "B", false, "999")),
            SplitType.EXACT)
    assertThat(out.valid).isTrue()
    assertThat(out.input.values).containsExactly(1L, 10000L)
  }

  @Test
  fun `invalid text fails instead of silently zeroing`() {
    assertThat(parseInput(100L, rows(SplitRow(1, "A", true, "abc")), SplitType.EXACT).valid)
        .isFalse()
    assertThat(
            parseInput(
                    100L,
                    rows(SplitRow(1, "A", true, "60"), SplitRow(2, "B", true, "30")),
                    SplitType.EXACT)
                .valid)
        .isFalse()
    assertThat(
            parseInput(
                    100L,
                    rows(SplitRow(1, "A", true, "50"), SplitRow(2, "B", true, "40")),
                    SplitType.PERCENT)
                .valid)
        .isFalse()
  }

  @Test
  fun `huge exact amounts fail instead of wrapping`() {
    assertThat(
            parseInput(
                    Long.MAX_VALUE,
                    rows(
                        SplitRow(1, "A", true, "100000000000000000"),
                        SplitRow(2, "B", true, "100000000000000000")),
                    SplitType.EXACT)
                .valid)
        .isFalse()
    assertThat(parseInput(100L, rows(SplitRow(1, "A", true, "10.005")), SplitType.EXACT).valid)
        .isFalse()
  }

  @Test
  fun `huge percent and share weights fail instead of clamping`() {
    assertThat(parseInput(100L, rows(SplitRow(1, "A", true, "100000000")), SplitType.PERCENT).valid)
        .isFalse()
    assertThat(
            parseInput(100L, rows(SplitRow(1, "A", true, "9223372036854775807")), SplitType.SHARES)
                .valid)
        .isFalse()
  }

  // `Math.round(it * 100)` silently wraps: "1e20" is a valid Double and 1e20 * 100 wrapped
  // to a positive amount, so Save was enabled and a template was stored worth 18.6 quintillion
  // currency units. majorToMinor rejects what cannot be represented rather than wrapping it.
  @Test
  fun `amounts too large to represent are rejected rather than wrapped`() {
    // The exact string that used to slip through, and the same defect one order
    // of magnitude down: both move past Long.MAX_VALUE minor units.
    assertThat(majorToMinor("1e20")).isNull()
    assertThat(majorToMinor("1e19")).isNull()
    // The boundary is where it actually falls, not where it looks like it should.
    // Long.MAX_VALUE minor units is 92233720368547758.07, so the largest whole
    // major amount still representable is 92233720368547758 — and it is accepted,
    // because BigDecimal keeps it at scale 0 rather than rounding it. Only the
    // fraction past that overflows.
    assertThat(majorToMinor("92233720368547758")).isEqualTo(9223372036854775800L)
    assertThat(majorToMinor("92233720368547758.07")).isEqualTo(9223372036854775807L)
    assertThat(majorToMinor("92233720368547758.08")).isNull()
  }

  @Test
  fun `major to minor handles the cases the editor relies on`() {
    assertThat(majorToMinor("12.34")).isEqualTo(1234L)
    assertThat(majorToMinor("12")).isEqualTo(1200L)
    assertThat(majorToMinor("0.001")).isNull() // below a cent, not silently zero
    assertThat(majorToMinor("abc")).isNull()
    assertThat(majorToMinor("")).isNull()
  }

  @Test
  fun `money formats extreme values`() {
    // The sign sits after the currency code, the way a statement writes it, so
    // a negative balance does not read as a currency called "-USD".
    assertThat(money(Long.MIN_VALUE, "USD")).isEqualTo("USD -92233720368547758.08")
    assertThat(money(-5L, "USD")).isEqualTo("USD -0.05")
    assertThat(money(123L, "USD")).isEqualTo("USD 1.23")
  }
}
