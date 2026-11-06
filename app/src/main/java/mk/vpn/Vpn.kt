package mk.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Client for OpenVPN for Android's remote-control API (the AIDL service it exports for apps like this).
 * One binding is shared by the setup screen and the accessibility service.
 */
object Vpn {
    const val PACKAGE = "de.blinkt.openvpn"
    private const val ACTION = "de.blinkt.openvpn.api.IOpenVPNAPIService"

    /** OpenVPN's last reported state: CONNECTED, NOPROCESS (off), WAIT, AUTH, AUTH_FAILED, … */
    data class Status(val uuid: String?, val state: String, val message: String) {
        val connected get() = state == "CONNECTED"
        val stopped get() = state.isEmpty() || state in setOf("NOPROCESS", "EXITING", "AUTH_FAILED", "DISCONNECTED", "NONETWORK")
    }

    private val _status = MutableStateFlow(Status(null, "", ""))
    val status: StateFlow<Status> = _status

    private val api = MutableStateFlow<IOpenVPNAPIService?>(null)
    private var bound = false
    private var listening = false
    private val heard = MutableStateFlow(false)

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            _status.value = Status(uuid, state.orEmpty(), message.orEmpty())
            heard.value = true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            api.value = IOpenVPNAPIService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api.value = null
            listening = false
        }

        override fun onBindingDied(name: ComponentName?) {
            api.value = null
            listening = false
            bound = false
        }
    }

    fun installed(context: Context) =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** The bound service, or null if OpenVPN for Android is missing or doesn't answer. */
    private suspend fun service(context: Context): IOpenVPNAPIService? {
        api.value?.let { return it }
        if (!bound) {
            val intent = Intent(ACTION).setPackage(PACKAGE)
            bound = runCatching { context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) return null
        }
        return withTimeoutOrNull(5_000) { api.filterNotNull().first() }
    }

    /** Runs [block] against the API; null when OpenVPN is unreachable or refuses (no permission yet). */
    private suspend fun <T> call(context: Context, block: (IOpenVPNAPIService) -> T): T? {
        val s = service(context) ?: return null
        if (!listening) listening = runCatching { s.registerStatusCallback(callback) }.isSuccess
        return runCatching { block(s) }.getOrNull()
    }

    /** OpenVPN answers and has allowed this app to use its API. */
    suspend fun canControl(context: Context): Boolean {
        val s = service(context) ?: return false
        return runCatching { s.prepare(context.packageName) == null }.getOrDefault(false)
    }

    /** Intent that asks the user to let this app control OpenVPN; null once allowed. */
    suspend fun permissionIntent(context: Context): Intent? =
        service(context)?.let { s -> runCatching { s.prepare(context.packageName) }.getOrNull() }

    /** Android's own "allow OpenVPN to set up a VPN" dialog; null once OpenVPN has it. */
    suspend fun vpnPermissionIntent(context: Context): Intent? = call(context) { it.prepareVPNService() }

    /** Our PIA profiles in OpenVPN: profile name → UUID. */
    private suspend fun profiles(context: Context): Map<String, String> =
        call(context) { it.profiles }.orEmpty().filter { it.mName.startsWith(Region.PROFILE_PREFIX) }.associate { it.mName to it.mUUID }

    /** UUID of [region]'s profile, adding it (with the saved PIA login) the first time it's needed. */
    suspend fun profileFor(context: Context, region: Region): String? =
        profiles(context)[region.profileName]
            ?: call(context) { it.addNewVPNProfile(region.profileName, true, region.config(context, Login.get(context))) }?.mUUID

    /** Deletes our profiles, e.g. after the PIA login changed; they come back with the new login when next used. */
    suspend fun removeProfiles(context: Context) {
        for (uuid in profiles(context).values) call(context) { it.removeProfile(uuid) }
    }

    /** Connects [region] (OpenVPN drops the current connection); returns the profile's UUID. */
    suspend fun start(context: Context, region: Region): String? {
        val uuid = profileFor(context, region) ?: return null
        return uuid.takeIf { call(context) { api -> api.startProfile(uuid) } != null }
    }

    /**
     * OpenVPN's own connect screen for [uuid]: asks for the login when it's missing and then connects.
     * Must be started from a foreground app (or this accessibility service) — Android blocks OpenVPN
     * from opening it by itself in the background.
     */
    fun launchIntent(uuid: String): Intent = Intent(Intent.ACTION_MAIN)
        .setClassName(PACKAGE, "$PACKAGE.LaunchVPN")
        .putExtra("$PACKAGE.shortcutProfileUUID", uuid)
        .putExtra("$PACKAGE.showNoLogWindow", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun disconnect(context: Context) {
        call(context) { it.disconnect() }
    }

    /** Waits until [uuid] is connected, or gives up after [timeoutMs]. */
    suspend fun awaitConnected(uuid: String, timeoutMs: Long = 25_000): Boolean =
        withTimeoutOrNull(timeoutMs) { status.first { it.uuid == uuid && it.connected } } != null

    /** Waits until OpenVPN reports working on [uuid] at all (connecting or connected). */
    suspend fun awaitStarted(uuid: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { status.first { it.uuid == uuid && !it.stopped } } != null

    /** Region of the profile OpenVPN is using (or connecting) right now; null when off or not ours. */
    suspend fun current(context: Context): Region? {
        val profiles = profiles(context)
        // Right after binding OpenVPN hasn't reported yet: give its first status a moment
        if (!heard.value) withTimeoutOrNull(1_000) { heard.first { it } }
        val s = status.value
        if (s.uuid == null || s.stopped) return null
        val name = profiles.entries.firstOrNull { it.value == s.uuid }?.key ?: return null
        return Regions.byProfileName(context, name)
    }
}
