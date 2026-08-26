package dev.beancount.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import dev.beancount.core.entitlement.UpdateManifest
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloading and installing a signed update. **Expert edition only.**
 *
 * This file lives in `src/expert` and may never move: `:verifyPlayFlavorBoundaries` fails the
 * build if `PackageInstaller`, `ACTION_INSTALL_PACKAGE` or `REQUEST_INSTALL_PACKAGES` appears
 * anywhere the play edition compiles (docs/distribution.md 2.1, D3). Google Play delivers
 * updates for that edition; an app that could update itself around the store is exactly what
 * the policy forbids.
 *
 * Three things are checked before Android is asked to install anything, and none of them is
 * redundant:
 *
 * 1. **The manifest's signature** — `UpdateVerifier`, before this class is reached at all.
 * 2. **The download's SHA-256**, against the value in the signed manifest. The signature says
 *    what the bytes should be; this says the bytes are those.
 * 3. **The APK's own signing certificate**, against the running app's. Android would refuse a
 *    mismatch anyway, but it refuses with an opaque failure long after the download — and
 *    checking here means the app can say *why*.
 */
class ApkUpdate(private val context: Context) {

    sealed interface Result {
        /** Downloaded, verified, and waiting for the user to confirm the install. */
        data class ReadyToInstall(val apk: File) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Downloads [manifest] and verifies it, but does **not** install.
     *
     * Downloading and installing are separate calls because they are separate decisions: the
     * user agrees to fetch a file, sees what it is, and then agrees to run it.
     */
    suspend fun download(manifest: UpdateManifest): Result = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "update-${manifest.versionCode}.apk")
        target.delete()

        val downloaded = runCatching {
            val connection = java.net.URI(manifest.url).toURL().openConnection()
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.getInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (downloaded.isFailure) {
            target.delete()
            return@withContext Result.Failed(
                "The update could not be downloaded: " +
                    (downloaded.exceptionOrNull()?.message ?: "no connection"),
            )
        }

        val actual = sha256(target)
        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
            target.delete()
            return@withContext Result.Failed(
                "The downloaded file is not the one the update was signed for. Nothing has " +
                    "been installed.",
            )
        }

        if (!signedLikeThisApp(target)) {
            target.delete()
            return@withContext Result.Failed(
                "The downloaded app was signed by a different developer than the one you " +
                    "have installed. Nothing has been installed — this is what would happen " +
                    "if someone replaced the download.",
            )
        }

        Result.ReadyToInstall(target)
    }

    /**
     * Hands [apk] to Android's installer.
     *
     * This does not install anything. It opens the system's install confirmation, which the
     * user has to accept — a sideloading app cannot install silently, and this one does not
     * try to look as though it can.
     */
    fun requestInstall(apk: File): Result {
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("update", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(INSTALL_RESULT).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            Result.ReadyToInstall(apk)
        }.getOrElse {
            Log.w(TAG, "install session failed: ${it.javaClass.simpleName}")
            Result.Failed("Android would not start the install: ${it.message}")
        }
    }

    /** Whether the downloaded APK carries the same signing certificate as this app. */
    private fun signedLikeThisApp(apk: File): Boolean = runCatching {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val downloaded = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?.signingInfo ?: return false
        val installed = manager.getPackageInfo(context.packageName, flags)
            .signingInfo ?: return false

        val theirs = downloaded.apkContentsSigners.map { it.toCharsString() }.toSet()
        val ours = installed.apkContentsSigners.map { it.toCharsString() }.toSet()
        theirs.isNotEmpty() && theirs == ours
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val INSTALL_RESULT = "dev.beancount.app.INSTALL_RESULT"
        private const val TAG = "ApkUpdate"
    }
}
