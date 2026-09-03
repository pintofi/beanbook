package dev.beancount.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The shape scale, rounded past Material's baseline towards Material 3 Expressive.
 *
 * Expressive's own tokens — `largeIncreased`, `extraLargeIncreased`, `extraExtraLarge` —
 * are **internal** in Material3 1.4.0, which is what the pinned Compose BOM resolves to.
 * Not experimental, not opt-in: `ExperimentalMaterial3ExpressiveApi` is itself internal at
 * this version, so there is no annotation that unlocks them. Verified by compiling against
 * them rather than by reading a changelog (B23). They become public in 1.5.0, and a BOM
 * upgrade is its own milestone with the corpus re-run, so this file expresses the same
 * intent through the five slots the stable `Shapes` API does expose.
 *
 * The values are not arbitrary: [large] and [extraLarge] are set to what Expressive calls
 * `largeIncreased` (20dp) and `extraLargeIncreased` (32dp), so when the upgrade lands this
 * scale can be swapped for the tokens with the corners barely moving.
 *
 * Restraint is deliberate. This is a ledger: it draws dense tables of figures, and rounding
 * everything to a pill would cost horizontal room in the exact rows where digits need to
 * line up. So the increase is one step per slot, not a wholesale re-shaping, and the
 * amount column is untouched.
 *
 * What visibly changes: cards (`medium`) 12dp → 16dp, the add FAB (`large`) 16dp → 20dp,
 * and dialogs (`extraLarge`) 28dp → 32dp.
 */
internal val BeancountShapes = Shapes(
    // Text field corners. Kept nearly flat: a field is a place to type, and a strongly
    // rounded one reads as a button.
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
