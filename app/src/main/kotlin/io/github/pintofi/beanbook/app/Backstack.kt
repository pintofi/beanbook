package io.github.pintofi.beanbook.app

/**
 * What is open on top of the current destination.
 *
 * These are the things Back should close, in the order they were opened. Modelling them as a
 * list is the point: they used to be four independent booleans with four `BackHandler`s whose
 * `enabled` conditions each restated every condition above them —
 *
 *     BackHandler(enabled = adding) { ... }
 *     BackHandler(enabled = !adding && moreSection != null) { ... }
 *     BackHandler(enabled = !adding && moreSection == null && showContribute) { ... }
 *
 * — so opening a fifth thing meant editing the other four, and forgetting one silently broke
 * Back. That happened once already when the add form moved to a button.
 */
internal sealed interface Overlay {
    /** The add-entry form, opened from the floating button or a widget. */
    data object AddEntry : Overlay

    /** A section of the More hub. */
    data class Section(val section: MoreSection) : Overlay

    /** The Contribute sheet. */
    data object Contribute : Overlay
}

/**
 * Where the user is: a destination, and whatever is open over it.
 *
 * Back pops one thing at a time — the newest overlay, then back to Home, then out of the app.
 * That last step returns `null` so the caller can let the system handle it rather than
 * swallowing the gesture, which is what makes Back leave the app from Home instead of doing
 * nothing.
 */
internal data class Backstack(
    val tab: Surface = Surface.HOME,
    val overlays: List<Overlay> = emptyList(),
) {
    val top: Overlay? get() = overlays.lastOrNull()

    val isAdding: Boolean get() = top == Overlay.AddEntry
    val openSection: MoreSection? get() = (top as? Overlay.Section)?.section
    val isContributing: Boolean get() = top == Overlay.Contribute

    /** Opens [overlay] on top, replacing an identical one rather than stacking it twice. */
    fun open(overlay: Overlay): Backstack =
        if (top == overlay) this else copy(overlays = overlays + overlay)

    /** Switches destination. Overlays belong to where they were opened, so they close. */
    fun switchTo(surface: Surface): Backstack = Backstack(tab = surface)

    /**
     * One step back, or `null` when there is nowhere left to go inside the app.
     *
     * `null` rather than an unchanged copy, so the caller can tell "handled" from "let the
     * system take it" without comparing states.
     */
    fun back(): Backstack? = when {
        overlays.isNotEmpty() -> copy(overlays = overlays.dropLast(1))
        tab != Surface.HOME -> copy(tab = Surface.HOME)
        else -> null
    }
}
