package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.mirror.MirrorStatus
import io.github.pintofi.beanbook.core.data.MirrorAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mirroring says what it is doing, at the top, at all times (B65).
 *
 * The screen used to open with settings and say nothing about state: a pass in flight looked
 * exactly like one that had never run, and the only report was a message left over from the
 * last one -- "3 file(s) synced" still on screen for a folder that had since gone away.
 */
@RunWith(AndroidJUnit4::class)
class MirrorStatusTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        status: MirrorStatus,
        folder: String? = "content://folder",
        enabled: Boolean = false,
        conflicts: List<MirrorAction.Conflict> = emptyList(),
    ) {
        compose.setContent {
            MaterialTheme {
                MirrorScreen(
                    enabled = enabled,
                    folder = folder,
                    status = status,
                    conflicts = conflicts,
                    onChooseFolder = {},
                    onEnabledChanged = {},
                    onSyncNow = {},
                    onKeepMine = {},
                    onKeepTheirs = {},
                )
            }
        }
    }

    @Test fun withNoFolderItSaysMirroringIsOff() {
        show(MirrorStatus.Off, folder = null)

        compose.onNodeWithText("Mirroring is off").assertIsDisplayed()
        compose.onNodeWithText("Choose a folder to keep a copy of this project in.")
            .assertIsDisplayed()
    }

    @Test fun theSwitchCannotBeUsedBeforeAFolderIsChosen() {
        show(MirrorStatus.Off, folder = null)

        // And it says why, rather than sitting there greyed out with no explanation.
        compose.onNodeWithContentDescription("Mirror after every change").assertIsNotEnabled()
        compose.onNodeWithText("Choose a folder first.").assertIsDisplayed()
    }

    @Test fun aChosenFolderWithTheSwitchOffIsMirroringByHand() {
        show(MirrorStatus.Manual(lastPassAtEpochMs = null))

        compose.onNodeWithText("Mirroring by hand").assertIsDisplayed()
    }

    @Test fun theSwitchSaysMirroringGoesBothWays() {
        show(MirrorStatus.Watching(lastPassAtEpochMs = System.currentTimeMillis()))

        compose.onNodeWithText("Mirroring after every change").assertIsDisplayed()
        compose.onNodeWithText("Both ways: writes here go out, and changes there come back.")
            .assertIsDisplayed()
    }

    @Test fun aPassInFlightIsCountedOnScreen() {
        show(MirrorStatus.Copying(done = 3, total = 8))

        compose.onNodeWithText("Copying 3 of 8…").assertIsDisplayed()
        // Mirror now is refused while a pass is running: two overlapping passes race on the
        // same files and could resolve a conflict by accident.
        compose.onNodeWithContentDescription("Mirror now").assertIsNotEnabled()
    }

    @Test fun conflictsOutrankEverythingElse() {
        val conflict = MirrorAction.Conflict(
            "files/transactions.beancount",
            MirrorAction.Conflict.Kind.BOTH_CHANGED,
        )
        show(MirrorStatus.NeedsDecision(1), conflicts = listOf(conflict))

        compose.onNodeWithText("1 file needs your decision").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Keep this device's version of files/transactions.beancount",
        ).assertIsDisplayed()
    }

    @Test fun aFailedPassSaysTheProjectIsUnchanged() {
        show(MirrorStatus.Failed("that folder is no longer reachable"))

        compose.onNodeWithText("The last pass did not finish").assertIsDisplayed()
        compose.onNodeWithText("that folder is no longer reachable. Your project is unchanged.")
            .assertIsDisplayed()
    }

    @Test fun theStatusIsAboveTheSettings() {
        show(MirrorStatus.Manual(lastPassAtEpochMs = null))

        val status = compose.onNodeWithText("Mirroring by hand").fetchSemanticsNode()
        val heading = compose.onNodeWithText("Mirror to a folder").fetchSemanticsNode()
        assert(status.positionInRoot.y < heading.positionInRoot.y) {
            "the status was not the first thing on the screen"
        }
    }

    @Test fun theFolderIsOfferedBeforeTheSwitchThatNeedsIt() {
        show(MirrorStatus.Manual(lastPassAtEpochMs = null))

        val folder = compose.onNodeWithContentDescription("Change the folder", substring = true)
            .fetchSemanticsNode()
        val switch = compose.onNodeWithText("Mirror after every change").fetchSemanticsNode()
        assert(folder.positionInRoot.y < switch.positionInRoot.y) {
            "the switch was above the folder it depends on"
        }
    }

    @Test fun aCleanScreenShowsNoConflictSection() {
        show(MirrorStatus.Watching(lastPassAtEpochMs = null))

        compose.onAllNodesWithText("Needs your decision").assertCountEquals(0)
    }
}
