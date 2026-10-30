package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Units, At cost and Market value row at double text size on a narrow phone: a label may wrap
 * between words, but never inside one ("Mar / ket / val / ue").
 */
@RunWith(AndroidJUnit4::class)
class ConnectedChoiceRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val labels = listOf("Units", "At cost", "Market value")

    private fun render(widthDp: Int, fontScale: Float) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(widthDp.dp).fillMaxHeight().padding(horizontal = 16.dp)) {
                        ConnectedChoiceRow(options = labels, selected = labels.last(), onSelect = {}, label = { it })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The text of each rendered line of [label], as the layout broke it. */
    private fun lines(label: String): List<String> {
        val node = compose.onNodeWithText(label).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val layout = results.single()
        return (0 until layout.lineCount).map {
            layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim()
        }
    }

    private fun assertNoWordIsBroken() {
        labels.forEach { label ->
            assertEquals(
                "\"$label\" was broken inside a word: ${lines(label)}",
                label,
                lines(label).joinToString(" "),
            )
        }
    }

    @Test fun atDoubleTextOnANarrowPhoneNoLabelIsBrokenInsideAWord() {
        render(widthDp = 360, fontScale = 2f)
        assertNoWordIsBroken()
    }

    @Test fun atTheLargestTextSizeToo() {
        render(widthDp = 320, fontScale = 3f)
        assertNoWordIsBroken()
    }

    @Test fun atNormalSizeItStaysOneRow() {
        render(widthDp = 360, fontScale = 1f)
        val tops = labels.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("the three choices should share a row at normal size: $tops", tops.distinct().size == 1)
    }
}
