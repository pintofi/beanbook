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
import io.github.pintofi.beanbook.app.ui.theme.readableMeasureCentred

/** Where the source lives. The repository is public; nothing here is fetched from it. */
private const val SOURCE_URL = "https://github.com/PintoFi/beanbook"

/** Where to write about the app. */
private const val CONTACT_EMAIL = "pintofinan@gmail.com"

/**
 * Who made this, what it is built on, and how a new version arrives.
 *
 * This was the Updates section until the self-updater was removed (B40). With nothing left to
 * update, what remained was the screen every app has and this one did not: the identity of the
 * thing you are running. That matters more here than in most apps, because Beanbook is GPL
 * software handling your accounts — the licence entitles you to the source, so the source is a
 * button rather than a sentence, and the contact address is on screen so
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

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        io.github.pintofi.beanbook.app.ui.FolioPage(modifier = Modifier.readableMeasureCentred()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The mark in the theme's own colours (design-direction §4): it was fixed teal
                // while the rest of the app wore the wallpaper's colour (device audit 2026-10-10).
                io.github.pintofi.beanbook.app.ui.BeanbookMark(size = 72.dp)
                Text("Beanbook", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    "$version  ·  ${EditionFacts.NAME} edition",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Plain-text double-entry accounting, running Beancount and Fava on this " +
                        "device. Your ledger is a folder of text files you own.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            io.github.pintofi.beanbook.app.ui.FolioGroup {
                item {
                    io.github.pintofi.beanbook.app.ui.FolioRow(
                        title = "Source",
                        detail = "The code on GitHub, in your browser",
                        icon = null,
                        onClick = { open(SOURCE_URL) },
                        describe = "Source code on GitHub, opens in your browser",
                        opens = true,
                    ) {
                        Icon(painterResource(R.drawable.ic_github), contentDescription = null)
                    }
                }
                item {
                    io.github.pintofi.beanbook.app.ui.FolioRow(
                        title = "Email",
                        detail = "Write to the author",
                        icon = Icons.Filled.MailOutline,
                        onClick = { open("mailto:$CONTACT_EMAIL") },
                        describe = "Email $CONTACT_EMAIL, opens your mail app",
                        opens = true,
                    )
                }
            }

            io.github.pintofi.beanbook.app.ui.FolioHeading("This app")
            io.github.pintofi.beanbook.app.ui.FolioGroup {
                item { io.github.pintofi.beanbook.app.ui.FolioRow(title = "Author", detail = "PintoFi") }
                item {
                    io.github.pintofi.beanbook.app.ui.FolioRow(
                        title = "Licence",
                        detail = "GPL-2.0-only",
                        onClick = onOpenLicences,
                        describe = "Licence: GPL-2.0-only. Opens the full licence texts.",
                        opens = true,
                    )
                }
                builtOn?.let { item { io.github.pintofi.beanbook.app.ui.FolioRow(title = "Built on", detail = it) } }
            }
            // Said plainly rather than left to the licence text (GPL-2.0, sections 11-12).
            io.github.pintofi.beanbook.app.ui.FolioNote(
                "Beanbook comes with no warranty, as its GPL-2.0 licence states. Keep backups of your ledger.",
            )
        }
    }
}

