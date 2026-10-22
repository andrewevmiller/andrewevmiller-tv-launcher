package com.andrewevmiller.tvlauncher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.andrewevmiller.tvlauncher.theme.FieldTheme
import com.andrewevmiller.tvlauncher.theme.fieldTile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the JVM Compose harness works in this environment: render the launcher theme, focus a tile, and move
 * focus with a D-pad key. Real UI behaviour tests build on this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeHarnessSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dpadMovesFocusBetweenTiles() {
        val first = FocusRequester()
        rule.setContent {
            FieldTheme {
                Row {
                    Modifier.size(20.dp)
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(40.dp).testTag("a").focusRequester(first).fieldTile(onClick = {}),
                    )
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(40.dp).testTag("b").fieldTile(onClick = {}),
                    )
                }
            }
        }
        rule.runOnIdle { first.requestFocus() }
        rule.onNodeWithTag("a").assertIsFocused()
        rule.onNodeWithTag("a").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("b").assertIsFocused()
    }
}
