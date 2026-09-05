package io.github.pintofi.beanbook.core.entitlement

import java.io.File
import java.time.Instant

/**
 * Entitlement state on disk, as two tiny text files in app-private storage.
 *
 * Plain text on purpose: this is not a security boundary. The app is GPL, so anyone can
 * build it without the gate — obfuscating the trial file would buy nothing and would make
 * the honest framing in the Contribute screen a lie (docs/distribution.md §4.7).
 *
 * Deliberately outside the portable project tree, so it is never exported or backed up
 * with a ledger.
 */
class FileEntitlementStorage(private val directory: File) : EntitlementStorage {

    private val trialFile: File get() = File(directory, "trial")
    private val keyFile: File get() = File(directory, "key")
    private val purchaseFile: File get() = File(directory, "purchase")

    override fun readTrial(): TrialRecord? {
        val text = trialFile.takeIf { it.isFile }?.readText()?.trim() ?: return null
        val parts = text.split('\n')
        val started = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val seen = parts.getOrNull(1)?.toLongOrNull() ?: started
        return TrialRecord(Instant.ofEpochSecond(started), Instant.ofEpochSecond(seen))
    }

    override fun writeTrial(record: TrialRecord) {
        directory.mkdirs()
        // Write-then-rename, like every other durable write in this app: a half-written
        // trial file must not read as "no trial".
        val tmp = File(directory, "trial.tmp")
        tmp.writeText("${record.startedAt.epochSecond}\n${record.lastSeenAt.epochSecond}\n")
        tmp.renameTo(trialFile)
    }

    override fun readKey(): StoredKey? {
        val lines = keyFile.takeIf { it.isFile }?.readLines() ?: return null
        if (lines.size < 4) return null
        val tier = lines[0].toIntOrNull()?.let { Tier.ofLevel(it) } ?: return null
        return StoredKey(tier = tier, email = lines[1], orderId = lines[2], raw = lines[3])
    }

    override fun writeKey(key: StoredKey?) {
        directory.mkdirs()
        if (key == null) {
            keyFile.delete()
            return
        }
        val tmp = File(directory, "key.tmp")
        tmp.writeText("${key.tier.level}\n${key.email}\n${key.orderId}\n${key.raw}\n")
        tmp.renameTo(keyFile)
    }

    override fun readPurchase(): StoredPurchase? {
        val lines = purchaseFile.takeIf { it.isFile }?.readLines() ?: return null
        if (lines.size < 2) return null
        val tier = lines[0].toIntOrNull()?.let { Tier.ofLevel(it) } ?: return null
        return StoredPurchase(tier = tier, orderId = lines[1])
    }

    override fun writePurchase(purchase: StoredPurchase?) {
        directory.mkdirs()
        if (purchase == null) {
            purchaseFile.delete()
            return
        }
        val tmp = File(directory, "purchase.tmp")
        tmp.writeText("${purchase.tier.level}\n${purchase.orderId}\n")
        tmp.renameTo(purchaseFile)
    }
}
