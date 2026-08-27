package dev.beancount.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.beancount.app.plugins.PluginRow
import dev.beancount.app.currentPluginPolicy
import dev.beancount.app.ui.theme.BeancountTheme
import dev.beancount.app.ui.theme.ThemeChoice
import dev.beancount.core.data.PluginIntegrity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pixel gate, and proof that it catches the bug it exists for.
 *
 * The screens are rendered at a fixed size with dynamic colour off, so the background is the
 * app's own palette rather than whatever the device's wallpaper produced — a check that
 * samples the background cannot work if the background is a surprise.
 */
@RunWith(AndroidJUnit4::class)
class PixelGateTest {

    @get:Rule val compose = createComposeRule()

    private companion object {
        const val SURFACE = "pixel-gate-surface"

        /** Clear of the status bar, which is composited over the window this capture reads. */
        const val TOP_INSET = 64
    }

    private val TODAY: java.time.LocalDate = java.time.LocalDate.of(2026, 3, 4)

    private fun vocabulary() = dev.beancount.core.domain.LedgerVocabulary(
        accounts = listOf("Assets:Cash", "Expenses:Food"),
        payees = listOf("Cafe"),
        narrations = listOf("Lunch"),
        currencies = listOf("USD"),
        tags = emptyList(),
    )

    /** 16dp of screen padding, in pixels, less a pixel for antialiasing at the boundary. */
    private fun marginPx(density: Density): Int =
        with(density) { 16.dp.toPx() }.toInt() - 1

    /**
     * @param heightDp must fit inside the test window with [TOP_INSET] to spare. A surface
     *   larger than the window is silently clipped by it, and then every coordinate in the
     *   captured image refers to something other than what was asked for — an 800dp request
     *   came back 667dp tall and the margin scan was reading the middle of the screen.
     */
    private fun render(widthDp: Int = 411, heightDp: Int = 480, content: @Composable () -> Unit) {
        compose.setContent {
            // Dynamic colour off: the check samples the corner to learn the background, and
            // a wallpaper-derived palette makes that unpredictable between devices.
            BeancountTheme(choice = ThemeChoice.LIGHT, dynamicColour = false) {
                // The surface is pushed clear of the top of the window before being
                // captured. `captureToImage` reads the window's pixels for the node's
                // rectangle, and the system status bar is composited over that region — the
                // first runs reported "content" at rows 0..6, thirty pixels wide, in the
                // top-left corner, which was the clock.
                Box(Modifier.padding(top = TOP_INSET.dp)) {
                    Box(
                        Modifier
                            .testTag(SURFACE)
                            .requiredWidth(widthDp.dp)
                            .requiredHeight(heightDp.dp)
                            .background(
                                androidx.compose.material3.MaterialTheme.colorScheme.background,
                            ),
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun check(name: String = "capture"): List<String> {
        val density = Density(compose.density.density, compose.density.fontScale)
        // The tagged surface, not the root. `onRoot()` captures the whole window, which
        // includes the system status bar — the first run reported "content" at rows 0..6,
        // 30px wide, in the top-left corner. That was the clock.
        val image = compose.onNodeWithTag(SURFACE).captureToImage()
        // Saved so a failure can be looked at rather than guessed about.
        runCatching {
            val ctx = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext
            java.io.File(ctx.externalCacheDir, "$name.png").outputStream().use { out ->
                image.asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        return PixelGate.assertNothingDrawnInMargin(image, marginPx(density)).let { problems ->
            if (problems.isEmpty()) problems
            else problems + "captured ${image.width}x${image.height}"
        }
    }

    // ---- the gate finds nothing wrong with correct screens ---------------------------------

    @Test fun theAppearanceScreenDrawsNothingInItsSideMargins() {
        render {
            AppearanceScreen(
                choice = ThemeChoice.SYSTEM,
                dynamicColour = true,
                dynamicColourSupported = true,
                onChoice = {},
                onDynamicColour = {},
                hideWidgetAmounts = false,
                onHideWidgetAmounts = {},
            )
        }

        val problems = check("appearance")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun thePluginsScreenDrawsNothingInItsSideMargins() {
        val plugin = dev.beancount.core.data.InstalledPlugin(
            id = "tag-large",
            name = "tag_large.py",
            kind = dev.beancount.core.domain.PluginKind.SOURCE_FILE,
            sourceSha256 = "a".repeat(64),
            installedSha256 = "b".repeat(64),
            sizeBytes = 618,
            installedAtEpochMs = 1_756_000_000_000,
            sourceLabel = "tag_large.py",
            installedUnderPolicy = "expert",
            enabled = true,
            modules = listOf("tag_large.py"),
        )

        render {
            PluginsScreen(
                rows = listOf(PluginRow(plugin, PluginIntegrity.Unchanged)),
                safeMode = false,
                policy = currentPluginPolicy(),
                onInstall = {},
                onSetEnabled = { _, _ -> },
                onRemove = {},
                onSetSafeMode = {},
            )
        }

        val problems = check()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ---- and it catches the bug the semantics tree could not see ---------------------------

    /**
     * The control.
     *
     * This is the shape of the real defect: a control positioned so it is drawn past the
     * right edge of its container. Its *layout* bounds stay inside — which is exactly why
     * three different semantics-tree checks stayed green — and only the drawing is cut.
     *
     * If this ever stops failing, the gate has stopped working and every green run above
     * means nothing.
     */
    /**
     * Every other screen, at the width where clipping shows up.
     *
     * The gate covered two screens because those were the two where a clipped switch had
     * already been found, which is checking the place the bug was rather than the places it
     * could be (N11). These render at 320dp — narrower than any phone in use — because a
     * layout that survives 320dp survives the rest, and because the defect this catches is
     * one the semantics tree structurally cannot see: a control that is drawn outside its
     * parent still reports sensible bounds.
     */
    /**
     * Every other screen, at the width where clipping shows up.
     *
     * The gate covered two screens because those were the two where a clipped switch had
     * already been found, which is checking where the bug was rather than where it could be
     * (N11). 320dp is narrower than any phone in use: a layout that survives it survives the
     * rest, and the defect this catches is one the semantics tree structurally cannot see —
     * a control drawn outside its parent still reports sensible bounds.
     *
     * One test per screen rather than a loop: `setContent` may be called once per rule, and
     * a loop over it fails with "has already set content" after the first pass.
     */
    @Test fun theQuickAddScreenDrawsNothingInItsSideMargins() {
        render(widthDp = 320) { QuickAddScreen(vocabulary(), onSave = {}, today = TODAY) }
        val problems = check("quick-add")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun theDirectiveEntryScreenDrawsNothingInItsSideMargins() {
        render(widthDp = 320) { DirectiveEntryScreen(vocabulary(), onSave = {}, today = TODAY) }
        val problems = check("directive-entry")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // The Licences screen is deliberately not here, and the gate is what established that it
    // should not be. It reported 246 pixels in the left margin — Material `ListItem`s and the
    // dividers between them run edge to edge by design, as they do in the More menu, so on a
    // full-bleed list "nothing in the side margin" is not the property being tested for. The
    // margin rule belongs to screens laid out with `padding(16.dp)`; applying it to a list
    // would mean insetting the list to satisfy a test, which is backwards. The licence *text*
    // view is padded and is covered at 320dp and 200% font by AccessibilityTest.

    @Test fun theRemoteScreenDrawsNothingInItsSideMargins() {
        render(widthDp = 320) {
            RemoteScreen(
                status = dev.beancount.core.engine.EngineStatus.Stopped,
                capabilities = null,
                savedAddress = "http://192.168.1.5:5000",
                savedUsername = "someone",
                pendingRetry = true,
                onConnect = { _, _, _ -> },
                onDisconnect = {},
                onForget = {},
                onRetryPending = {},
                onDiscardPending = {},
                message = "Saved on the server.",
            )
        }
        val problems = check("remote")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun theGateCatchesAControlDrawnOffTheRightEdge() {
        render(widthDp = 411, heightDp = 200) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .offset(x = 380.dp, y = 80.dp)
                        .size(48.dp)
                        .background(Color(0xFF006A6A)),
                )
            }
        }

        val problems = check()
        assertTrue(
            "the gate did not notice a control drawn past the right edge",
            problems.any { it.startsWith("right edge") },
        )
    }

    @Test fun theGateIsNotFooledByContentThatMerelyReachesTheMargin() {
        // A control that stops exactly at the padding boundary is correct, and must not be
        // reported. A gate that cries wolf is one that gets switched off.
        render(widthDp = 411, heightDp = 200) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .offset(x = 16.dp, y = 80.dp)
                        .size(48.dp)
                        .background(Color(0xFF006A6A)),
                )
            }
        }

        val problems = check()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
