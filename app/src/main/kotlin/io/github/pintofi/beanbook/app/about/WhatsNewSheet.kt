package io.github.pintofi.beanbook.app.about

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.BeancountApplication
import io.github.pintofi.beanbook.app.BuildConfig
import io.github.pintofi.beanbook.app.ui.theme.BeanbookShape
import io.github.pintofi.beanbook.app.ui.theme.Measure
import io.github.pintofi.beanbook.core.data.WhatsNew
import io.github.pintofi.beanbook.core.data.WhatsNewRelease
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The changelog bundled with this edition, up to the installed version; empty if it cannot be read. */
internal fun loadWhatsNew(context: Context): List<WhatsNewRelease> = runCatching {
    val text = context.assets.open("whats-new/CHANGELOG.md").bufferedReader().use { it.readText() }
    WhatsNew.parse(text, upTo = BuildConfig.VERSION_NAME, expert = EditionFacts.NAME == "Expert")
}.getOrDefault(emptyList())

/**
 * What changed, release by release, in a sheet from the bottom of the screen like the Filters sheet.
 *
 * The newest release is open and the older ones are folded, each a row that says its version and date. The
 * text is the changelog itself, so it cannot disagree with it. It is plain: no links (the sheet opens nothing
 * and works with no network), no motion of its own beyond the sheet's slide, and one Close button at the foot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhatsNewSheet(onDismiss: () -> Unit, releases: List<WhatsNewRelease>) {
    val newest = releases.firstOrNull()?.version
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = BeanbookShape.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = Measure.gutter)
                .padding(bottom = Measure.section)
                .semantics { paneTitle = "What's new in Beanbook ${newest ?: ""}".trim() },
            verticalArrangement = Arrangement.spacedBy(Measure.snug),
        ) {
            Text(
                "What's new",
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Measure.snug),
            ) {
                if (releases.isEmpty()) {
                    Text(
                        "The list of changes could not be read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                releases.forEachIndexed { index, release -> ReleaseBlock(release, startsOpen = index == 0) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ReleaseBlock(release: WhatsNewRelease, startsOpen: Boolean) {
    var open by rememberSaveable(release.version) { mutableStateOf(startsOpen) }
    val title = "${release.version}${release.date?.let { " · ${longDate(it)}" }.orEmpty()}"
    Column(verticalArrangement = Arrangement.spacedBy(Measure.tight)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Fold $title" else "Open $title", role = Role.Button) {
                    open = !open
                }
                .semantics {
                    heading()
                    stateDescription = if (open) "Open" else "Folded"
                },
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            release.groups.forEach { group ->
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Measure.tight).semantics { contentDescription = group.title },
                )
                group.entries.forEach { entry ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Measure.snug)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Text(styled(entry), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun longDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
}.getOrDefault(iso)

/** An entry with its `**bold**` and `code` marks turned into styles. Expert-only entries lose their prefix. */
@Composable
private fun styled(entry: String): AnnotatedString {
    val code = SpanStyle(fontFamily = FontFamily.Monospace)
    val label = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    return remember(entry, code, label) {
        buildAnnotatedString {
            var rest = entry
            if (rest.startsWith(WhatsNew.EXPERT_PREFIX)) {
                withStyle(label) { append("Expert  ") }
                rest = rest.removePrefix(WhatsNew.EXPERT_PREFIX).trim()
            }
            val marks = Regex("""\*\*(.+?)\*\*|`(.+?)`""")
            var at = 0
            for (m in marks.findAll(rest)) {
                append(rest.substring(at, m.range.first))
                val bold = m.groups[1]?.value
                if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
                else withStyle(code) { append(m.groups[2]?.value.orEmpty()) }
                at = m.range.last + 1
            }
            append(rest.substring(at))
        }
    }
}

/**
 * Opens [WhatsNewSheet] once for each version after an update, from the top of Home. Returns whether it is open,
 * so the page can leave its other notice for the next launch.
 *
 * Only Home hosts it, and Home is composed only when it is the page in front and no entry is being added, so it
 * never opens over the entry form, setup, a restore or a passphrase prompt. A first install records the version
 * without showing anything: the introduction is for new people.
 */
@Composable
internal fun WhatsNewHost(freshInstall: Boolean? = null): Boolean {
    val context = LocalContext.current
    val app = context.applicationContext as? BeancountApplication ?: return false
    var open by remember {
        mutableStateOf(
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val fresh = freshInstall ?: (info.firstInstallTime == info.lastUpdateTime)
                app.whatsNewRecord.isDue(BuildConfig.VERSION_NAME, freshInstall = fresh)
            }.getOrDefault(false),
        )
    }
    if (!open) return false
    val releases = remember { loadWhatsNew(context) }
    WhatsNewSheet(
        releases = releases,
        onDismiss = {
            runCatching { app.whatsNewRecord.markSeen(BuildConfig.VERSION_NAME) }
            open = false
        },
    )
    return true
}
