package io.github.pintofi.beanbook.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.backup.BackupSettings
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The passphrase for scheduled backups is kept behind the real Android Keystore (#68). It has to
 * survive a new `BackupSettings`, which is what the worker builds, and it must not sit in the
 * state folder as text.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledBackupPassphraseTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "bk-pass").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test fun aSavedPassphraseIsReadBackByAnotherInstance() {
        val settings = BackupSettings(dir)
        assertFalse(settings.encryptsScheduled())
        assertNull(settings.passphrase())

        settings.setScheduledPassphrase("correct horse".toCharArray())

        val later = BackupSettings(dir)
        assertTrue(later.encryptsScheduled())
        assertArrayEquals("correct horse".toCharArray(), later.passphrase())
        assertFalse(dir.walkTopDown().filter { it.isFile }.any { "correct horse" in it.readText(Charsets.ISO_8859_1) })
        later.setScheduledPassphrase(null)
    }

    @Test fun turningItOffForgetsThePassphrase() {
        val settings = BackupSettings(dir)
        settings.setScheduledPassphrase("one".toCharArray())
        settings.setScheduledPassphrase(null)

        assertFalse(BackupSettings(dir).encryptsScheduled())
        assertNull(BackupSettings(dir).passphrase())
        assertEquals(0, dir.listFiles()?.count { it.name.startsWith("backup-passphrase") })
    }
}
