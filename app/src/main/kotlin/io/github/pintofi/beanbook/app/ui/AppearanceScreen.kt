package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.DarkColours
import io.github.pintofi.beanbook.app.ui.theme.LightColours
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred

/**
 * How the app looks.
 *
 * Small on purpose. Every option here is one someone might genuinely need — dark mode for
 * reading at night, dynamic colour off when a wallpaper produces a palette that fights a
 * table of numbers — and none of it touches a ledger.
 *
 * The four themes are chosen from swatches -- a small page of each, paper, ink and rule -- so a
 * person sees what they are choosing rather than reading four words (design-direction §7).
 */
@Composable
fun AppearanceScreen(
    choice: ThemeChoice,
    dynamicColour: Boolean,
    dynamicColourSupported: Boolean,
    onChoice: (ThemeChoice) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
    hideWidgetAmounts: Boolean,
    onHideWidgetAmounts: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
    FolioPage(modifier = Modifier.readableMeasureCentred()) {
        FolioHeading("Theme")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            ThemeChoice.entries.forEach { option ->
                ThemeSwatch(
                    option = option,
                    selected = choice == option,
                    onClick = { onChoice(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        FolioHeading("Colour")
        FolioGroup {
            item {
                if (dynamicColourSupported) {
                    FolioSwitchRow(
                        title = "Dynamic colour",
                        detail = "Take accent colours from your wallpaper. Paper, ink and rules stay the app's own.",
                        checked = dynamicColour,
                        onCheckedChange = onDynamicColour,
                        icon = Icons.Outlined.Palette,
                        describe = "Dynamic colour. Take accent colours from your wallpaper",
                    )
                } else {
                    FolioRow(
                        title = "Dynamic colour",
                        detail = "Dynamic colour needs Android 12 or newer. This phone uses the app's own colours.",
                        icon = Icons.Outlined.Palette,
                    )
                }
            }
            item {
                FolioSwitchRow(
                    title = "Hide amounts in widgets",
                    detail = "Widgets show a label and a time but no figures. They are hidden on the " +
                        "lock screen regardless, where anyone who picks up your phone can read them.",
                    checked = hideWidgetAmounts,
                    onCheckedChange = onHideWidgetAmounts,
                    icon = Icons.Outlined.VisibilityOff,
                    describe = "Hide amounts in widgets",
                )
            }
        }

        // A preview, because a colour choice you cannot see the effect of is a colour choice
        // people make once and never revisit.
        FolioHeading("Preview")
        FolioGroup {
            item {
                FolioBody {
                    // Both sides of one entry in ink: the minus sign says which way the money
                    // went (design-direction §4). Colour is kept for a difference -- this month
                    // against the last -- which is the one place the app paints a figure.
                    AmountRow("Expenses:Food:Groceries", "262.00", Color.Unspecified)
                    AmountRow("Assets:Bank:Checking", "-262.00", Color.Unspecified)
                    LedgerHairline()
                    AmountRow("More than last month", "+40.00", BeancountTheme.ledger.positive)
                    AmountRow("Less than last month", "-15.00", BeancountTheme.ledger.negative)
                }
            }
        }
        FolioNote(
            "Postings and balances are in ink: in double-entry a negative figure is ordinary. " +
                "Colour marks a difference, and the sign still says which way. Figures use a " +
                "fixed-width face so decimal points line up down a column.",
        )
    }
    }
}

/**
 * One theme as a small page: its paper, a rule down the margin, two lines of ink and a figure.
 * Drawn from the app's own fixed palettes, so the swatch is the theme even while another is on.
 */
@Composable
private fun ThemeSwatch(
    option: ThemeChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val light = LightColours
    val dark = DarkColours
    Column(
        modifier = modifier
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = "Theme: ${option.label}" }
            .heightIn(min = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Measure.tight),
    ) {
        Surface(
            shape = io.github.pintofi.beanbook.app.ui.theme.BeanbookShape.notice,
            border = BorderStroke(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(SWATCH_ASPECT),
        ) {
            Canvas(Modifier.fillMaxWidth()) {
                when (option) {
                    ThemeChoice.LIGHT -> page(light.background, light.onSurface, light.primary)
                    ThemeChoice.DARK -> page(dark.background, dark.onSurface, dark.primary)
                    ThemeChoice.BLACK -> page(Color.Black, dark.onSurface, dark.primary)
                    ThemeChoice.SYSTEM -> {
                        page(light.background, light.onSurface, light.primary)
                        val half = Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        clipPath(half) { page(dark.background, dark.onSurface, dark.primary) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp).size(16.dp),
                )
            }
            Text(
                if (option == ThemeChoice.SYSTEM) "System" else option.label,
                style = if (selected) {
                    MaterialTheme.typography.labelLargeEmphasized
                } else {
                    MaterialTheme.typography.labelLarge
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A page in miniature: paper, the margin rule, two lines of words and a figure at the end. */
private fun DrawScope.page(paper: Color, ink: Color, rule: Color) {
    drawRect(paper)
    val margin = size.width * 0.18f
    drawRect(rule, Offset(margin, size.height * 0.14f), Size(size.width * 0.03f, size.height * 0.72f))
    val left = margin + size.width * 0.1f
    val bar = size.height * 0.07f
    listOf(0.24f to 0.5f, 0.46f to 0.32f, 0.68f to 0.42f).forEachIndexed { index, (top, width) ->
        drawRect(
            ink.copy(alpha = if (index == 0) 0.9f else 0.45f),
            Offset(left, size.height * top),
            Size(size.width * width, bar),
        )
    }
    drawRect(ink, Offset(size.width * 0.66f, size.height * 0.68f), Size(size.width * 0.22f, bar))
}

/** The swatch's shape: a little taller than wide, like a page. */
private const val SWATCH_ASPECT = 0.82f

@Composable
private fun AmountRow(account: String, amount: String, colour: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            breakableAccount(account),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        // The same composable the journal draws with, so this preview cannot drift from what
        // it claims to preview.
        MoneyText(amount, color = colour)
    }
}
