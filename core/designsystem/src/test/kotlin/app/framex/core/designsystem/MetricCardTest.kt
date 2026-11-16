package app.framex.core.designsystem

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.framex.core.common.Metric
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MetricCardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unavailableShowsReasonAndNoValue() {
        rule.setContent {
            FrameXTheme { MetricCard<Float>("CPU", Metric.Unavailable("blocked by Android"), valueText = { "99%" }) }
        }
        rule.onNodeWithText("Unavailable").assertIsDisplayed()
        rule.onNodeWithText("blocked by Android").assertIsDisplayed()
        rule.onNodeWithText("99%").assertDoesNotExist()
    }

    @Test fun availableShowsValue() {
        rule.setContent { FrameXTheme { MetricCard("RAM", Metric.Available(0.5f), valueText = { "50% used" }) } }
        rule.onNodeWithText("50% used").assertIsDisplayed()
    }

    @Test fun notYetMeasuredShowsPlaceholderNotANumber() {
        rule.setContent { FrameXTheme { MetricCard<Float>("RAM", null, valueText = { "50%" }) } }
        rule.onNodeWithText("Measuring…").assertIsDisplayed()
        rule.onNodeWithText("50%").assertDoesNotExist()
    }
}
