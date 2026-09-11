package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shape by role, because one rounded rectangle for everything is how an app stops having a
 * shape at all (B74).
 *
 * The rule this design follows: **data is square, interventions are round.** A row of figures
 * is part of a ruled page and has no corners; a thing that interrupts the page -- a notice, a
 * dialog, a sheet, the button that writes to the file -- is a separate object and is shaped
 * like one. That distinction does real work. It means a person can tell, without reading, the
 * difference between their ledger and the app talking about their ledger.
 *
 * The Material [Shapes] below is what themed components pick up automatically. [BeanbookShape]
 * is the vocabulary this app composes with directly, and it includes the two shapes Material
 * has no slot for: the flat data surface and the stamp.
 */
internal val BeancountShapes = Shapes(
    // Chips and the smallest containers. Square-ish: these sit inside data.
    extraSmall = RoundedCornerShape(4.dp),
    // Fields and controls. Enough radius to read as touchable, not enough to read as a pill.
    small = RoundedCornerShape(10.dp),
    // Notices and cards: the page interrupting itself.
    medium = RoundedCornerShape(14.dp),
    // Menus and the larger containers inside a dialog.
    large = RoundedCornerShape(20.dp),
    // Dialogs and sheets. Material takes both from this slot, so the two share a radius
    // rather than the app fighting the component for it on twelve call sites.
    extraLarge = RoundedCornerShape(28.dp),
)

/** The shapes this app composes with by name, including the two Material does not have. */
object BeanbookShape {

    /**
     * Data. A journal row, a balance line, a ruled group: no corners, because they are part of
     * the page rather than objects on it.
     */
    val data: Shape = RectangleShape

    /**
     * A field, a segmented control, a button.
     *
     * Typed as the rounded shape rather than as [Shape] because Material's segmented button
     * needs a corner-based shape to derive the ends of a row from, and a control that is a
     * pill at the ends and square in the middle is the default this design is replacing.
     */
    val control: RoundedCornerShape = RoundedCornerShape(10.dp)

    /** A notice: the app saying something about the ledger. */
    val notice: Shape = RoundedCornerShape(14.dp)

    /** A dialog. The same radius Material gives an `AlertDialog` from the theme. */
    val dialog: Shape = RoundedCornerShape(28.dp)

    /** A bottom sheet, rounded at the top only. */
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /**
     * A stamp: the small mark beside an entry that says cleared, pending, or attention.
     *
     * Not a pill. A pill is the default shape of every status chip in every app, and this one
     * is meant to read like something pressed onto paper -- square with the corners just
     * knocked off.
     */
    val stamp: Shape = RoundedCornerShape(3.dp)

    /**
     * The header band at the top of a primary surface: square against the status bar, curved
     * where it meets the page, so the band reads as a sheet laid over the paper.
     */
    val band: Shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

    /** A thumbnail or preview tile. */
    val tile: Shape = RoundedCornerShape(8.dp)

    /** The same corner size the controls use, for building a partial shape. */
    val controlCorner: CornerSize = CornerSize(10.dp)
}
