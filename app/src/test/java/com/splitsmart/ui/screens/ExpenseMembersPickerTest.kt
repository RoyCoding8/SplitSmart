package com.splitsmart.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.splitsmart.data.SplitType
import com.splitsmart.ui.vm.SplitRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpenseMembersPickerTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun expandedPickerScrollsThroughAllMembers() {
    val members = (1..30).map { SplitRow(it.toLong(), "Member $it") }
    compose.setContent {
      MaterialTheme {
        ExpenseMembersPicker(members, false, SplitType.EQUAL, null, "USD") {}
      }
    }

    compose.onNodeWithContentDescription("Expand members").performClick()
    compose.onNodeWithText("Split members").assertIsDisplayed()
    compose.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
    compose.onNodeWithText("Member 30").assertIsDisplayed()
    compose.onNodeWithText("Done").performClick()
  }
}
