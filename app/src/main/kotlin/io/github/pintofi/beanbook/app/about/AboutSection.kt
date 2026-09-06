package io.github.pintofi.beanbook.app.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.R
import io.github.pintofi.beanbook.app.ui.loadLicences

/** Where the source lives. The repository is public; nothing here is fetched from it. */
private const val SOURCE_URL = "https://github.com/PintoFi/beanbook"

/** The address on the contribution-key emails, so a reply reaches the same place. */
private const val CONTACT_EMAIL = "pintofinan@gmail.com"

/**
 * Who made this, what it is built on, and how a new version arrives.
 *
 * This was the Updates section until the self-updater was removed (B40). With nothing left to
 * update, what remained was the screen every app has and this one did not: the identity of the
 * thing you are running. That matters more here than in most apps, because Beanbook is GPL
 * software handling your accounts — the licence entitles you to the source, so the source is a
 * button rather than a sentence, and the address that sends contribution keys is on screen so
 * a reply is not detective work.
 *
 * The address and the URL are on the buttons and nowhere else: printing them again underneath
 * as text said the same thing twice on a screen whose whole job is to be short.
 *
 * The Beancount and Fava versions come from `licences.json`, which `tools/licences.py`
 * generates from a built APK and CI checks for drift. A hand-written version number on an
 * About screen is a version number that stops being true.
 */
@Composable
fun AboutSection(onOpenLicences: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (build ${info.longVersionCode})"
        }.getOrDefault("unknown")
    }

    // Named from the same generated manifest the Licences screen reads, so the two cannot
    // disagree and neither can drift from what is actually bundled.
    val builtOn = remember {
        runCatching {
            val (_, components) = loadLicences(context)
            listOf("beancount", "fava")
                .mapNotNull { name -> components.firstOrNull { it.name == name } }
                .joinToString("  ·  ") { "${it.name.replaceFirstChar(Char::uppercase)} ${it.version}" }
                .ifBlank { null }
        }.getOrNull()
    }

    fun open(uri: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppMark()
            Text("Beanbook", style = MaterialTheme.typography.headlineSmall)
            Text(
                "$version  ·  ${EditionFacts.NAME} edition",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Plain-text double-entry accounting, running Beancount and Fava on this " +
                    "device. Your ledger is a folder of text files you own.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(
                onClick = { open(SOURCE_URL) },
                modifier = Modifier.semantics {
                    contentDescription = "Source code on GitHub, opens in your browser"
                },
            ) {
                Icon(painterResource(R.drawable.ic_github), contentDescription = null)
                Text("  Source")
            }
            OutlinedButton(
                onClick = { open("mailto:$CONTACT_EMAIL") },
                modifier = Modifier.semantics {
                    contentDescription = "Email $CONTACT_EMAIL, opens your mail app"
                },
            ) {
                Icon(Icons.Filled.MailOutline, contentDescription = null)
                Text("  Email")
            }
        }

        HorizontalDivider()

        // Their own column, with no spacing of its own: `ListItem` already carries the
        // vertical padding Material specifies, and the outer 12dp would be added on top of it
        // and pull the rows apart into a list of unrelated things.
        Column {
            Fact("Author", "PintoFi")
            Fact(
                label = "Licence",
                value = "GPL-2.0-only",
                onClick = onOpenLicences,
                description = "Licence: GPL-2.0-only. Opens the full licence texts.",
            )
            builtOn?.let { Fact("Built on", it) }
        }

    }
}

/**
 * One line of the specification: what it is, then what it says.
 *
 * Label first, because that is the order every other list in the app uses and because these
 * are read as a table rather than scanned for a name. A row with an [onClick] states what
 * tapping it does in its own content description; the rest are text and say so by having none.
 */
@Composable
private fun Fact(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    description: String? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * The launcher icon, at the size a launcher would show it.
 *
 * Drawn rather than loaded: `painterResource` cannot take an `AdaptiveIconDrawable`, which is
 * what `@mipmap/ic_launcher` resolves to. So the two layers are recreated here — background
 * colour, foreground vector, circular mask. The foreground is a 108dp drawable whose visible
 * area is the central 72dp, so it is laid out at 108dp inside a 72dp clip; sizing it to the
 * box instead would show the icon 1.5x too large with its margins cropped away.
 */
@Composable
private fun AppMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(108.dp),
        )
    }
}
