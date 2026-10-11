package io.github.pintofi.beanbook.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemColors
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pintofi.beanbook.app.ui.LedgerHairline
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.MotionRole
import io.github.pintofi.beanbook.app.ui.theme.rememberAnimationsEnabled

// The app's chrome around the destinations: the bar, the rail, and the add button
// (docs/design-direction.md §7, §8). Split out of LedgerApp.kt and AppChrome.kt so the shell
// stays a list of decisions rather than a wall of components.

/**
 * A destination's icon, with the rule that marks the one you are on (B74).
 *
 * The rule grows from the centre rather than fading in: it is the same gesture as the column
 * rule drawing down a surface, at the size of a tab, so moving between destinations reads as
 * the same app doing the same thing. Material's filled pill is replaced rather than recoloured
 * -- a lozenge under a column of figures is the loudest thing on the screen, and this design
 * marks things with rules.
 */
@Composable
internal fun DestinationMark(icon: ImageVector, selected: Boolean) {
    val colour = BeancountTheme.ledger.rule
    val animate = rememberAnimationsEnabled()
    val extent by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        // The expressive control spring: the mark is the bar's selection indicator, a control
        // and not a figure, so it may land with the scheme's small overshoot.
        animationSpec = if (animate) MotionRole.control() else snap(),
        label = "destination",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .width(20.dp)
                .height(2.dp)
                .clearAndSetSemantics { },
        ) {
            if (extent > 0f) {
                drawRect(
                    color = colour,
                    topLeft = Offset(x = size.width * (1f - extent) / 2f, y = 0f),
                    size = Size(size.width * extent, size.height),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Icon(icon, contentDescription = null)
    }
}

/**
 * A destination's label, which never clips (design-direction §5).
 *
 * At 200% text the bar used to become a scrolling row of words with no icons, and "More" was
 * cut to "M" (device audit 2026-10-10). Now every destination keeps its icon, and its label is
 * capped at one and a half times its normal size and then shrunk, if it still has to be, to fit
 * the width its item has: five words that all fit beat five that do not.
 */
@Composable
private fun DestinationLabel(text: String) {
    val scale = LocalDensity.current.fontScale
    val base = MaterialTheme.typography.labelMedium.fontSize.value
    // In sp, which the system multiplies by the font scale again: so this is the size that,
    // once scaled, is at most LABEL_SCALE_CAP times the label's normal size.
    val capped = base * minOf(scale, LABEL_SCALE_CAP) / scale
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        autoSize = TextAutoSize.StepBased(
            minFontSize = (capped * LABEL_MIN_FRACTION).sp,
            maxFontSize = capped.sp,
            stepSize = 0.5.sp,
        ),
    )
}

/** How far a destination label grows with the system font before it stops. */
private const val LABEL_SCALE_CAP = 1.5f

/** How far it may then shrink to fit a narrow item. */
private const val LABEL_MIN_FRACTION = 0.7f

/** The bar's and the rail's colours: ink for the chosen destination, no pill behind it. */
@Composable
private fun destinationColours(): NavigationItemColors = ShortNavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onSurface,
    selectedTextColorTopIconPosition = MaterialTheme.colorScheme.onSurface,
    selectedTextColorStartIconPosition = MaterialTheme.colorScheme.onSurface,
    selectedIndicatorColor = Color.Transparent,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * The destination bar on a phone: Material 3 Expressive's short bar, on the band, with the
 * app's rule as the selection mark (docs/material-expressive.md).
 *
 * The footer rule (B74): a hairline above it, no shadow. Nothing is selected while the entry
 * form is open (B61): the form is not a destination.
 */
@Composable
internal fun LedgerBottomBar(selected: Surface, adding: Boolean, onSelect: (Surface) -> Unit) {
    Column {
        LedgerHairline()
        ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Surface.entries.forEach { surface ->
                val chosen = !adding && selected == surface
                ShortNavigationBarItem(
                    selected = chosen,
                    onClick = { onSelect(surface) },
                    icon = { DestinationMark(surface.icon, chosen) },
                    label = { DestinationLabel(surface.label) },
                    colors = destinationColours(),
                    // No contentDescription: the label supplies one, and setting both made
                    // TalkBack say "Reports, Reports" on every destination.
                )
            }
        }
    }
}

/**
 * The navigation rail, for windows too wide for a bottom bar, with the add button in its header
 * (design-direction §8): in landscape the floating button sat in the far corner, a hand's width
 * from the content it adds to; on the rail it is beside the destinations, where a hand already is.
 */
@Composable
internal fun LedgerNavigationRail(
    selected: Surface,
    adding: Boolean,
    showAdd: Boolean,
    onAdd: () -> Unit,
    onSelect: (Surface) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = {
            if (showAdd) {
                FloatingActionButton(
                    onClick = onAdd,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = FloatingActionButtonDefaults.loweredElevation(),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add an entry")
                }
            }
        },
    ) {
        // Scrolls when the window is shorter than the rail, so no destination is ever out of
        // reach (B98). On a phone in landscape -- about 410dp tall -- the add button leaves the
        // rail for the corner of the page ([railHoldsAdd]), and the five destinations fit.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Surface.entries.forEach { surface ->
            val chosen = !adding && selected == surface
            NavigationRailItem(
                selected = chosen,
                onClick = { onSelect(surface) },
                icon = { DestinationMark(surface.icon, chosen) },
                label = { DestinationLabel(surface.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        }
    }
}

/**
 * Whether the rail has room for the add button above its five destinations (B98). Below about
 * 480dp of height -- a phone on its side -- it does not: "More" was pushed off the bottom and
 * "Reports" lost its icon. There the button floats in the page's corner, as on a phone held
 * upright.
 */
@Composable
internal fun railHoldsAdd(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= SHORT_RAIL_DP

/** The window height below which the add button leaves the rail. */
private const val SHORT_RAIL_DP = 480

/**
 * Whether the page under the add button is being scrolled down (design-direction §7).
 *
 * Read from the scroll every surface already dispatches, at the shell, so no screen has to
 * report it: a finger moving the page up -- reading further down -- sends the button aside,
 * and the smallest move back brings it. The button covered the last figures on a page while
 * the page moved under it (device audit 2026-10-10); now it is out of the way exactly then.
 */
@Stable
internal class ActionScroll : NestedScrollConnection {
    var readingOn by mutableStateOf(false)
        private set

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Only a scroll that moved something counts: a fling against the end of the list, or
        // a drag on a page too short to scroll, should not hide the button forever.
        if (consumed.y < -SCROLL_SLOP) readingOn = true
        if (consumed.y > SCROLL_SLOP) readingOn = false
        // A pull downwards that the page has no room for -- at the top of a list, or on a page
        // opened after the button stepped aside (an entry page closing back to the Journal) --
        // also brings it back, so it is never hidden with no scroll left to recall it.
        if (available.y > SCROLL_SLOP && source == NestedScrollSource.UserInput) readingOn = false
        return Offset.Zero
    }

    /** A new destination starts with the button in view. */
    fun reset() {
        readingOn = false
    }

    private companion object {
        const val SCROLL_SLOP = 1f
    }
}

/**
 * The add button: "New entry", with its icon, extended where there is room for the words and
 * a plain round button where there is not (200% text).
 */
@Composable
internal fun AddEntryButton(onClick: () -> Unit) {
    val roomForWords = LocalDensity.current.fontScale <= 1.3f
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = roomForWords,
        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
        text = { Text("New entry", maxLines = 1) },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        // The description is on the button because the label is not always shown; without it
        // the round form is an unnamed button to a screen reader.
        modifier = Modifier.semantics { contentDescription = "Add an entry" },
    )
}

/**
 * Calls [release] on a tap that nothing beneath took (B98): a tap on blank page, not on a
 * control. `detectTapGestures` waits for an unconsumed press, and a button, a field, a chip or
 * a clickable row consumes its own, so taps on those never reach here; a scroll is a drag, not a
 * tap, and does not either.
 */
internal fun Modifier.releaseFocusOnBlankTap(release: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { release() })
        },
    )

/** The key a page's kept state is filed under: a destination, or a More section within it. */
internal fun pageKey(surface: Surface, section: MoreSection? = null): String =
    if (section == null) "page:${surface.name}" else "page:${surface.name}:${section.name}"

/**
 * [content] with its remembered-and-saved state -- scroll positions, list positions -- filed
 * under [key] in [pages], so it comes back when the page does (B98). A null key keeps nothing.
 */
@Composable
internal fun KeptPage(
    pages: androidx.compose.runtime.saveable.SaveableStateHolder,
    key: String?,
    content: @Composable () -> Unit,
) {
    if (key == null) content() else pages.SaveableStateProvider(key, content)
}
