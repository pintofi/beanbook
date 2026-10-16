package io.github.pintofi.beanbook.core.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Writing a file so that it is still there, whole, after the power goes.
 *
 * `FileOutputStream.close()` hands the bytes to the kernel, and a process death keeps them.
 * Power loss does not: a copy or a rename that was never flushed can come back empty or missing
 * even though the program saw it succeed. The write protocol has to be able to say that the
 * snapshot and the journal record exist *before* the ledger is replaced, so everything it
 * depends on is flushed to storage, and the directory entry that names it is flushed too.
 */
internal object Durable {

    fun writeBytes(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    /** [from] copied to [to], flushed. The parent folder of [to] is created if it is missing. */
    fun copy(from: File, to: File) {
        to.parentFile?.mkdirs()
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { out ->
                input.copyTo(out)
                out.flush()
                out.fd.sync()
            }
        }
    }

    /**
     * Flushes the folder itself, so the names created or renamed in it survive.
     *
     * Opened through a channel: opening a directory for writing fails with EISDIR on Linux.
     * Best effort, because some filesystems refuse it, but it has to be able to succeed.
     */
    fun syncDirectory(directory: File?) {
        if (directory == null) return
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
