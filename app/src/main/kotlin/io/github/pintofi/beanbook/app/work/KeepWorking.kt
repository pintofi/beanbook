package io.github.pintofi.beanbook.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.pintofi.beanbook.app.R
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics

/**
 * Keeps the app running while it finishes something the user started (B93).
 *
 * Installing a plugin downloads and unpacks for tens of seconds, and saving an entry with a
 * receipt files a PDF and writes the ledger twice. Both were interrupted by switching apps or
 * turning the screen off: Android is free to stop a process nobody is looking at, and a
 * sleeping CPU does not finish a download.
 *
 * Two things hold the work in place for exactly as long as it runs, and no longer:
 * - a **foreground service**, with a notification saying what is being done, so the process
 *   keeps foreground priority after the user leaves; and
 * - a **partial wake lock**, so the CPU keeps running with the screen off. It has a timeout,
 *   so a stuck job cannot keep the phone awake for ever.
 *
 * Android does not allow a foreground service to start while the app is in the background.
 * Every caller starts work from a tap, so this is the rare case; when it happens the wake
 * lock still holds, and [onUnprotected] tells the user to keep the app open instead.
 */
object KeepWorking {

    private val lock = Any()
    private val running = LinkedHashMap<Long, String>()
    private var next = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    /** Runs [block] with the app kept alive, describing it as [what] in the notification. */
    suspend fun <T> around(
        context: Context,
        what: String,
        onUnprotected: () -> Unit = {},
        block: suspend () -> T,
    ): T {
        val app = context.applicationContext
        val id = begin(app, what, onUnprotected)
        try {
            return block()
        } finally {
            end(app, id)
        }
    }

    private fun begin(context: Context, what: String, onUnprotected: () -> Unit): Long {
        val id: Long
        val first: Boolean
        synchronized(lock) {
            id = next++
            first = running.isEmpty()
            running[id] = what
            if (wakeLock == null) {
                wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Beanbook:work")
                    .apply {
                        setReferenceCounted(false)
                        acquire(WAKE_LOCK_TIMEOUT_MS)
                    }
            }
        }
        val started = runCatching {
            val intent = Intent(context, WorkService::class.java).putExtra(EXTRA_WHAT, what)
            if (first) context.startForegroundService(intent) else context.startService(intent)
        }
        if (started.isFailure) {
            Diagnostics.w(TAG, "could not keep the app in the foreground for: $what", started.exceptionOrNull())
            onUnprotected()
        }
        return id
    }

    private fun end(context: Context, id: Long) {
        val remaining: String?
        synchronized(lock) {
            running.remove(id)
            remaining = running.values.lastOrNull()
            if (remaining == null) {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                wakeLock = null
            }
        }
        runCatching {
            if (remaining == null) {
                context.stopService(Intent(context, WorkService::class.java))
            } else {
                context.startService(Intent(context, WorkService::class.java).putExtra(EXTRA_WHAT, remaining))
            }
        }
    }

    internal fun notification(context: Context, what: String): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Work in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Beanbook finishes something you started, such as " +
                        "installing a plugin or saving an entry."
                    setShowBadge(false)
                },
            )
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(what)
            .setContentText("Beanbook keeps working if you leave the app.")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Longer than any install measured (BeangrowOnDeviceTest's 37 MB closure), short enough
     * that a job that never ends lets the phone sleep again.
     */
    private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

    /** What to say when Android would not let the work be protected (B93). */
    const val KEEP_OPEN = "Keep Beanbook open and the screen on until this finishes."

    internal const val EXTRA_WHAT = "what"
    private const val CHANNEL = "work"
    private const val TAG = "KeepWorking"
}

/** The foreground service [KeepWorking] runs under. It does no work itself. */
class WorkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val what = intent?.getStringExtra(KeepWorking.EXTRA_WHAT) ?: "Finishing up"
        val notification = KeepWorking.notification(this, what)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Not restarted if Android stops it: the work it guards lives in the app's process,
        // and a restarted service with nothing to guard would only show a stale notification.
        return START_NOT_STICKY
    }

    private companion object {
        const val NOTIFICATION_ID = 7201
    }
}
