package com.splitsmart.ui.vm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

// The settle dialog seeds its amount field from a `Long` of minor units and, on Record,
// parses that text straight back. A Double cannot hold every Long, so above 2^53 the
// division rounds and the dialog offers an amount one minor unit off the debt it came
// from. A Double also prints the shortest round-tripping string, so 100 minor units came
// back as "1" and 1250 as "12.5" where every other amount reads "1.00" and "12.50".
class SettlePrefillTest {
  @Test
  fun `a prefilled amount parses back to the debt it came from`() {
    for (d in
        listOf(
            1L,
            100L,
            1250L,
            12345678901234L,
            1000000000000000L,
            9007199254740993L, // 2^53 + 1: the first Long a Double cannot represent
            92233720368547758L,
            Long.MAX_VALUE)) {
      assertThat(majorToMinor(minorToDecimal(d))).isEqualTo(d)
    }
  }

  @Test
  fun `a prefilled amount reads like every other amount in the app`() {
    assertThat(minorToDecimal(100L)).isEqualTo("1.00")
    assertThat(minorToDecimal(1250L)).isEqualTo("12.50")
    assertThat(minorToDecimal(5L)).isEqualTo("0.05")
    assertThat(minorToDecimal(0L)).isEqualTo("0.00")
  }
}
