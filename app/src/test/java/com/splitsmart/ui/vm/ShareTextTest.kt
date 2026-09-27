package com.splitsmart.ui.vm

import com.google.common.truth.Truth.assertThat
import com.splitsmart.core.settle.Transfer
import org.junit.jupiter.api.Test

class ShareTextTest {
  private val names = mapOf(7L to "Me", 9L to "Al")

  @Test
  fun `payback line formats money`() {
    assertThat(paybackLine("Al", "Me", 1250, "USD")).isEqualTo("Al pays Me USD 12.50")
  }

  @Test
  fun `share text lists whole plan`() {
    val out =
        settleShareText("Trip", listOf(Transfer(9, 7, 1250, "USD"), Transfer(7, 9, 300, "USD")), names)
    assertThat(out).isEqualTo("Settle up for Trip\nAl pays Me USD 12.50\nMe pays Al USD 3.00")
  }

  // The text that leaves the device has to agree with the currency each row names: a
  // single `cur` formatted every row in it, so a sheet reading "Al pays Me EUR 50.00"
  // produced, on Copy all, "Al pays Me USD 50.00" -- the same debt, relabelled, in the
  // message actually sent to the payee.
  @Test
  fun `share text labels each row in the currency that row is in`() {
    val out =
        settleShareText("Trip", listOf(Transfer(9, 7, 5000, "EUR"), Transfer(7, 9, 300, "USD")), names)
    assertThat(out).isEqualTo("Settle up for Trip\nAl pays Me EUR 50.00\nMe pays Al USD 3.00")
  }

  @Test
  fun `venmo uri prefills payment`() {
    assertThat(venmoUri("Al", 1250, "Trip settle-up"))
        .isEqualTo("venmo://paycharge?txn=pay&recipients=Al&amount=12.50&note=Trip+settle-up")
  }

  @Test
  fun `decimal uses dot regardless of locale`() {
    assertThat(minorToDecimal(1205)).isEqualTo("12.05")
  }

  // Routing the amount through a Double means the sign is applied after the division, and
  // at Long.MIN_VALUE the quotient is one too far from zero, so the payee is asked for a
  // different amount than the one recorded.
  @Test
  fun `decimal survives the ends of the Long range`() {
    assertThat(minorToDecimal(Long.MIN_VALUE)).isEqualTo("-92233720368547758.08")
    assertThat(minorToDecimal(Long.MAX_VALUE)).isEqualTo("92233720368547758.07")
    assertThat(minorToDecimal(0L)).isEqualTo("0.00")
    assertThat(minorToDecimal(-1L)).isEqualTo("-0.01")
    // The label has to name the same magnitude, and lead with the code rather than the
    // sign, where every other amount in the app reads "USD -12.50".
    assertThat(paybackLine("Al", "Me", Long.MIN_VALUE, "USD"))
        .isEqualTo("Al pays Me USD -92233720368547758.08")
    assertThat(money(-1250, "USD")).isEqualTo("USD -12.50")
    assertThat(money(1250, "USD")).isEqualTo("USD 12.50")
  }
}
