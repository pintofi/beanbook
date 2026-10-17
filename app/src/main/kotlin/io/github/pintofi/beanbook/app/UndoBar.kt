package io.github.pintofi.beanbook.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pintofi.beanbook.app.ui.LedgerReveal
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.app.undo.UndoController
import kotlinx.coroutines.delay

/**
 * The Undo bar: "Deleted 1 entry. Undo", over whatever screen is open.
 *
 * Hosted in the shell's snackbar slot, so Material's scaffold places it above the bottom bar and
 * lifts the add button over it rather than letting either cover the other. What it offers is
 * backed by the write protocol's durable snapshot ([UndoController], invariant 8); the bar only
 * shows the offer and asks for it. It steps away after the time Android recommends for a message
 * with an action -- longer while a screen reader is on -- and the offer stays good until then.
 *
 * The outcome of an undo is said once, politely, and then cleared. Both appear and leave through
 * [LedgerReveal], so with animations off they are simply there and then gone.
 */
@Composable
internal fun UndoBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Not every host is the app (a test may compose the shell on its own), and a missing
    // controller means there is nothing to offer, not an error.
    val undo = remember(context) { (context.applicationContext as? BeancountApplication)?.undo } ?: return
    UndoBar(undo, onUndone = {
        (context.applicationContext as? BeancountApplication)?.ledgerState?.reload()
    }, modifier = modifier)
}

@Composable
private fun UndoBar(undo: UndoController, onUndone: () -> Unit, modifier: Modifier) {
    val offer by undo.offer.collectAsStateWithLifecycle()
    val message by undo.message.collectAsStateWithLifecycle()
    val accessibility = LocalAccessibilityManager.current

    LaunchedEffect(offer) {
        val current = offer ?: return@LaunchedEffect
        val wait = accessibility?.calculateRecommendedTimeoutMillis(
            OFFER_MS,
            containsIcons = false,
            containsText = true,
            containsControls = true,
        ) ?: OFFER_MS
        delay(wait)
        // Only the offer that was shown: a newer write has replaced it and starts its own wait.
        if (undo.offer.value == current) undo.dismiss()
    }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val wait = accessibility?.calculateRecommendedTimeoutMillis(
            MESSAGE_MS,
            containsIcons = false,
            containsText = true,
            containsControls = false,
        ) ?: MESSAGE_MS
        delay(wait)
        undo.clearMessage()
    }

    Column(
        modifier = modifier.padding(horizontal = Measure.gutter, vertical = Measure.snug),
        verticalArrangement = Arrangement.spacedBy(Measure.snug),
    ) {
        LedgerReveal(visible = offer != null, label = "undoOffer") {
            // Held while it leaves, so the words do not vanish a frame before the bar.
            val shown = remember(offer) { offer } ?: return@LedgerReveal
            val words = shown.what.replaceFirstChar { it.uppercase() } + "."
            Snackbar(
                shape = BeanbookShape.notice,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                action = {
                    TextButton(
                        onClick = { undo.undo(onUndone) },
                        modifier = Modifier.semantics { contentDescription = "Undo: ${shown.what}" },
                    ) {
                        Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                },
            ) {
                Text(words, style = MaterialTheme.typography.bodyMedium)
            }
        }
        LedgerReveal(visible = message != null && offer == null, label = "undoMessage") {
            val shown = remember(message) { message }.orEmpty()
            Snackbar(
                shape = BeanbookShape.notice,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(shown, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** How long the offer stays before the recommended adjustment: Material's long duration. */
private const val OFFER_MS = 10_000L

/** How long the outcome is shown. */
private const val MESSAGE_MS = 4_000L
