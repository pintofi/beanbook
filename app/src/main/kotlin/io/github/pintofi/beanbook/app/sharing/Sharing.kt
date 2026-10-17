package io.github.pintofi.beanbook.app.sharing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import io.github.pintofi.beanbook.app.BeancountApplication
import io.github.pintofi.beanbook.app.MainActivity
import io.github.pintofi.beanbook.app.R
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import java.io.File
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where sharing on Wi-Fi stands. */
sealed interface SharingStatus {
    data object Off : SharingStatus
    data object Starting : SharingStatus

    /** Serving at [address], e.g. `http://192.168.1.20:8467`. */
    data class On(val address: String) : SharingStatus

    data class Failed(val reason: String) : SharingStatus
}

/**
 * The sign-in for sharing, kept on this device.
 *
 * The password is never stored: only a salted PBKDF2-SHA256 hash, which is also all that is
 * handed to the engine. The format is the one `sharing.py` checks against, pinned on both
 * sides by a test with the same fixed vector.
 *
 * In the app's private state folder, which is excluded from Android backups
 * (`allowBackup="false"`) and never part of a project, backup or mirror.
 */
class SharingSettings(stateDir: File) {
    private val file = File(stateDir, "sharing")

    val username: String? get() = read()?.first
    val passwordHash: String? get() = read()?.second
    val hasCredentials: Boolean get() = read() != null

    fun save(username: String, password: CharArray) {
        val hash = io.github.pintofi.beanbook.core.data.SharingPassword.hash(password)
        password.fill('\u0000')
        file.parentFile?.mkdirs()
        // One line each: a line break in the name would shift the hash onto the wrong line.
        file.writeText("${username.trim().replace('\n', ' ').replace('\r', ' ')}\n$hash\n")
    }

    fun forget() {
        file.delete()
    }

    private fun read(): Pair<String, String>? = runCatching {
        val lines = file.readLines()
        val name = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val hash = lines.getOrNull(1)?.takeIf { it.startsWith("pbkdf2_sha256$") } ?: return null
        name to hash
    }.getOrNull()
}

/**
 * Sharing Reports on the Wi-Fi network: turned on by the user, and kept on while it is on.
 *
 * Kept on means three things. A foreground service holds the process with a notification that
 * names the address and has a Stop button, so the phone says, for as long as it is true, that
 * other devices can reach it. When the engine restarts -- after a plugin install, say -- the
 * share goes with it, and is started again on the new engine. And when the Wi-Fi address
 * changes, the share moves with it rather than going on listening on an address the phone no
 * longer has.
 */
class SharingState(
    private val context: Context,
    private val engine: LocalEngineClient,
    private val engineStatus: StateFlow<EngineStatus>,
    private val scope: CoroutineScope,
    val settings: SharingSettings,
) {
    private val _status = MutableStateFlow<SharingStatus>(SharingStatus.Off)
    val status: StateFlow<SharingStatus> = _status.asStateFlow()

    /** Whether the user has sharing turned on; the engine stays up in the background while so. */
    @Volatile
    var wanted = false
        private set
    private var watcher: Job? = null
    private var network: ConnectivityManager.NetworkCallback? = null
    private var sharedOn: String? = null

    /** Starts sharing with the saved sign-in. */
    fun start() {
        if (!settings.hasCredentials) {
            _status.value = SharingStatus.Failed("Set a username and password first.")
            return
        }
        wanted = true
        _status.value = SharingStatus.Starting
        watchNetwork()
        watcher?.cancel()
        // Follows the engine: shares whenever Reports are running, and again after a restart.
        watcher = scope.launch {
            engineStatus.collect { status ->
                if (!wanted) return@collect
                when (status) {
                    is EngineStatus.Serving -> share()
                    is EngineStatus.Failed ->
                        _status.value = SharingStatus.Failed("Reports could not start: ${status.message}")
                    else -> if (_status.value is SharingStatus.On) _status.value = SharingStatus.Starting
                }
            }
        }
    }

    /** Stops sharing and signs every device out. */
    fun stop() {
        wanted = false
        watcher?.cancel()
        watcher = null
        unwatchNetwork()
        sharedOn = null
        scope.launch { engine.stopSharing() }
        context.stopService(Intent(context, SharingService::class.java))
        _status.value = SharingStatus.Off
    }

    private suspend fun share() {
        val host = wifiAddress()
        if (host == null) {
            _status.value = SharingStatus.Failed(
                "Not connected to Wi-Fi. Sharing needs a Wi-Fi or wired network the other devices are on.",
            )
            return
        }
        val username = settings.username ?: return
        val hash = settings.passwordHash ?: return
        engine.startSharing(host, PREFERRED_PORT, username, hash)
            .onSuccess { port ->
                val address = "http://$host:$port"
                sharedOn = host
                _status.value = SharingStatus.On(address)
                Diagnostics.i(TAG, "sharing on the local network")
                runCatching {
                    context.startForegroundService(
                        Intent(context, SharingService::class.java).putExtra(SharingService.EXTRA_ADDRESS, address),
                    )
                }.onFailure { Diagnostics.w(TAG, "no foreground service for sharing", it) }
            }
            .onFailure { error ->
                _status.value = SharingStatus.Failed(error.message ?: "Sharing could not start.")
            }
    }

    /** This device's IPv4 address on Wi-Fi or Ethernet, or null when it has neither. */
    private fun wifiAddress(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.asSequence()
            .filter { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@filter false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
            .mapNotNull { manager.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress || it.isLinkLocalAddress }
            ?.hostAddress
    }

    private fun watchNetwork() {
        if (network != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = moved()
            override fun onLost(network: Network) = moved()
            override fun onAvailable(network: Network) = moved()
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { network = callback }
    }

    private fun unwatchNetwork() {
        val callback = network ?: return
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        network = null
    }

    /** The network changed under the share: follow the new address, or say there is none. */
    private fun moved() {
        scope.launch {
            if (!wanted || engineStatus.value !is EngineStatus.Serving) return@launch
            val host = wifiAddress()
            if (host == sharedOn) return@launch
            engine.stopSharing()
            share()
        }
    }

    private companion object {
        const val TAG = "Sharing"

        /** A fixed port, so the address can be bookmarked; the engine falls back if it is taken. */
        const val PREFERRED_PORT = 8467
    }
}

/**
 * The foreground service that keeps sharing alive and says so.
 *
 * It does no serving itself -- that is the engine process -- but while it runs the app keeps
 * foreground priority, and its notification is the phone's own statement that it is reachable
 * from the network, with the address and a Stop button.
 */
class SharingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as BeancountApplication).sharingState.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val notification = notification(address)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun notification(address: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sharing on Wi-Fi", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown for as long as other devices on your network can open your Reports."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SharingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Sharing Reports on Wi-Fi")
            .setContentText("$address · sign-in required · not encrypted")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop sharing", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        private const val ACTION_STOP = "io.github.pintofi.beanbook.STOP_SHARING"
        private const val CHANNEL = "sharing"
        private const val NOTIFICATION_ID = 7202
    }
}
