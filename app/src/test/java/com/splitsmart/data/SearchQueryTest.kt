package com.splitsmart.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SearchQueryTest {
  @Test
  fun `tokens become prefix phrases`() {
    assertThat(ftsQuery("taco dinner")).isEqualTo("taco* dinner*")
  }

  @Test
  fun `punctuation only yields null`() {
    assertThat(ftsQuery("  \" * % ")).isNull()
    assertThat(ftsQuery("")).isNull()
  }

  @Test
  fun `like frag escapes wildcards`() {
    assertThat(likeFrag("  100%_\\ ")).isEqualTo("100\\%\\_\\\\")
  }
}
