package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Material 3 Expressive theme tokens (docs/material-expressive.md): the parts that are
 * plain values and can be checked without a device.
 */
class ExpressiveThemeTest {

    /** Animation off means every Material component gets an instant spec, not a quick one. */
    @Test fun withAnimationOffEveryMaterialSpecIsInstant() {
        val still = ledgerMotionScheme(animate = false)
        assertSame(StillMotionScheme, still)
        listOf(
            still.defaultSpatialSpec<Float>(),
            still.fastSpatialSpec<Float>(),
            still.slowSpatialSpec<Float>(),
            still.defaultEffectsSpec<Float>(),
            still.fastEffectsSpec<Float>(),
            still.slowEffectsSpec<Float>(),
        ).forEach { assertTrue("expected an instant spec, got $it", it is SnapSpec) }
    }

    @Test fun withAnimationOnMaterialGetsTheExpressiveSprings() {
        val spec = ledgerMotionScheme(animate = true).fastSpatialSpec<Float>()
        assertTrue("expected a spring, got $spec", spec is SpringSpec)
    }

    /**
     * Nothing the app animates in place overshoots except a control: a notice making room
     * under a column of figures, a fade and a press answer settle without a wobble.
     */
    @Test fun onlyTheControlSpringBounces() {
        listOf(
            MotionRole.local<Float>(),
            MotionRole.fade<Float>(),
            MotionRole.feedback<Float>(),
        ).forEach { spec ->
            assertTrue("expected a spring, got $spec", spec is SpringSpec)
            assertEquals(Spring.DampingRatioNoBouncy, (spec as SpringSpec).dampingRatio)
        }
        val control = MotionRole.control<Float>() as SpringSpec
        assertTrue("the control spring is the expressive one", control.dampingRatio < 1f)
    }

    /** Emphasized is the same line one weight heavier, never a different size. */
    @Test fun emphasizedStylesKeepTheScaleAndAddWeight() {
        with(BeancountTypography) {
            listOf(
                displayLarge to displayLargeEmphasized,
                displaySmall to displaySmallEmphasized,
                headlineMedium to headlineMediumEmphasized,
                titleLarge to titleLargeEmphasized,
                titleMedium to titleMediumEmphasized,
                titleSmall to titleSmallEmphasized,
                bodyMedium to bodyMediumEmphasized,
                labelLarge to labelLargeEmphasized,
                labelSmall to labelSmallEmphasized,
            ).forEach { (base, emphasized) -> assertHeavierAtTheSameSize(base, emphasized) }
        }
    }

    private fun assertHeavierAtTheSameSize(base: TextStyle, emphasized: TextStyle) {
        assertEquals(base.fontSize, emphasized.fontSize)
        assertEquals(base.lineHeight, emphasized.lineHeight)
        assertEquals(base.letterSpacing, emphasized.letterSpacing)
        val before = (base.fontWeight ?: FontWeight.Normal).weight
        val after = (emphasized.fontWeight ?: FontWeight.Normal).weight
        assertTrue("$after should be heavier than $before", after > before)
    }

    /** The fixed roles are this palette's, the same in both themes, and not Material's purple. */
    @Test fun fixedRolesAreTheAppsOwnAndDoNotChangeWithTheTheme() {
        val baseline = lightColorScheme()
        assertEquals(LightColours.primaryFixed, DarkColours.primaryFixed)
        assertEquals(LightColours.onTertiaryFixedVariant, DarkColours.onTertiaryFixedVariant)
        assertNotEquals(baseline.primaryFixed, LightColours.primaryFixed)
        assertNotEquals(baseline.secondaryFixedDim, LightColours.secondaryFixedDim)
        assertNotEquals(baseline.tertiaryFixed, LightColours.tertiaryFixed)
    }

    /** The Expressive shape slots stay on this app's scale, above the slot they extend. */
    @Test fun expressiveShapeSlotsStepUpFromTheAppsOwn() {
        assertNotEquals(BeancountShapes.large, BeancountShapes.largeIncreased)
        assertNotEquals(BeancountShapes.extraLarge, BeancountShapes.extraLargeIncreased)
        assertNotEquals(BeancountShapes.extraLargeIncreased, BeancountShapes.extraExtraLarge)
    }
}
