package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Mirror page names the folder as a person would (device audit 2026-10-10: it printed the
 * raw `content://` grant).
 */
class FolderNamesTest {

    @Test fun aFolderOnThePhoneIsItsPath() {
        assertEquals(
            "Documents/Hamza/Beanbook",
            humanFolder(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FHamza%2FBeanbook",
            ),
        )
    }

    @Test fun theRootOfThePhoneIsNamedRatherThanBlank() {
        assertEquals(
            "This phone's storage",
            humanFolder("content://com.android.externalstorage.documents/tree/primary%3A"),
        )
    }

    @Test fun aRemovableVolumeIsSaidToBeOne() {
        assertEquals(
            "SD card or USB storage · Ledgers",
            humanFolder("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ALedgers"),
        )
    }

    @Test fun aDocumentPathAfterTheTreeIsIgnored() {
        assertEquals(
            "Documents/Books",
            humanFolder(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBooks" +
                    "/document/primary%3ADocuments%2FBooks%2Fmain.beancount",
            ),
        )
    }

    @Test fun spacesAndPlusSignsSurviveDecoding() {
        assertEquals(
            "Documents/My Books+2026",
            humanFolder(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMy%20Books%2B2026",
            ),
        )
    }

    @Test fun aKnownCloudProviderIsNamedAndItsOpaqueIdIsNot() {
        assertEquals(
            "Google Drive",
            humanFolder(
                "content://com.google.android.apps.docs.storage/tree/" +
                    "acc%3D1%3Bdoc%3Dencoded%3D0B1a2b3c4d5e6f7g8h9i0jKLMNOP",
            ),
        )
    }

    @Test fun anUnknownProviderComesBackDecodedNotRaw() {
        assertEquals(
            "Shared",
            humanFolder("content://org.example.files/tree/root%3AShared"),
        )
    }

    @Test fun somethingThatIsNotATreeGrantIsShownAsItIs() {
        assertEquals("/sdcard/Beanbook", humanFolder("/sdcard/Beanbook"))
    }
}
