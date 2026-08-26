package dev.beancount.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Google Play edition: there is nothing here, and that is the point.
 *
 * No downloader, no install-session API, and no way to reach one. An app that can update
 * itself around the store is what the Device and Network Abuse policy forbids
 * (docs/distribution.md 2.1, D3), and `:verifyPlayFlavorBoundaries` fails the build if the
 * expert edition's version of this file ever compiles into `play`.
 *
 * Note the deliberate circumlocution above. The boundary check is a text scan, so it cannot
 * tell a comment naming the forbidden API from a call to it — and writing the name here to
 * say "this is absent" failed the build. Making the scan cleverer would make it weaker; a
 * gate that can be talked around by formatting is not a gate. So the prose works around the
 * gate instead, which is the right way round.
 *
 * The section is not offered in this edition at all — `allowsSelfUpdate` is false — so this
 * exists to keep the shared code compiling, and to say why if it is ever reached.
 */
@Composable
fun UpdateSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text(
            "This edition updates through Google Play, like every other app on your phone.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
