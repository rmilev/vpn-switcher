package mk.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Apps that use their region only when they ask for it. МК ТВ needs North Macedonia just for МРТ 1–5 live:
 * everything else in it (the archives, YouTube videos) works anywhere, and YouTube asks VPN users to
 * "sign in to confirm you're not a bot". So while such an app is in front the VPN is off unless it has asked.
 * The switch happens in the background without the hold screen: these apps retry by themselves when the
 * connection changes.
 */
object Demand {
    const val ACTION = "mk.vpn.action.NEED_VPN"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_NEED = "need"
    private const val TAG = "VpnDemand"

    private val ON_DEMAND = setOf("mk.tv")

    /** [pkg] asks for its region itself, so it isn't switched when it opens. */
    fun handles(context: Context, pkg: String) = pkg in ON_DEMAND && Assignments.get(context, pkg) is Target.To

    fun isOnDemand(pkg: String) = pkg in ON_DEMAND

    private val scope = MainScope()
    private var job: Job? = null
    private val wanted = HashSet<String>()

    /** The app in front (only real apps, not system pop-ups). */
    @Volatile var foreground: String? = null
        private set

    private val launchable = HashMap<String, Boolean>()

    /** Called for every window change: remembers the app in front, ignoring windows of things that aren't apps. */
    fun seen(context: Context, pkg: String) {
        val isApp = launchable.getOrPut(pkg) {
            val pm: PackageManager = context.packageManager
            pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
        }
        if (isApp) foreground = pkg
    }

    /** An on-demand app came to the front: give it a moment to say what it needs, then switch. */
    fun onForeground(context: Context, pkg: String) {
        foreground = pkg
        apply(context, pkg, delayMs = 800)
    }

    fun onRequest(context: Context, pkg: String, need: Boolean) {
        if (pkg !in ON_DEMAND) return
        Log.i(TAG, "$pkg ${if (need) "needs" else "no longer needs"} its VPN region")
        if (need) wanted += pkg else wanted -= pkg
        if (foreground == pkg && handles(context, pkg)) apply(context, pkg, delayMs = 0)
    }

    private fun apply(context: Context, pkg: String, delayMs: Long) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            if (foreground != pkg) return@launch
            val target = Assignments.get(app, pkg) as? Target.To ?: return@launch
            val current = Vpn.current(app)
            if (pkg in wanted) {
                if (current == target.region && !Vpn.status.value.stopped) return@launch
                Log.i(TAG, "connecting ${target.region.name} for $pkg")
                Vpn.start(app, target.region)
            } else if (current != null || !Vpn.status.value.stopped) {
                Log.i(TAG, "VPN off while $pkg doesn't need it")
                Vpn.disconnect(app)
            }
        }
    }
}

/** `mk.vpn.action.NEED_VPN` from an on-demand app: `need` = true when it starts needing its region, false when done. */
class DemandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Demand.ACTION) return
        val sender = if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
        val pkg = sender ?: intent.getStringExtra(Demand.EXTRA_PACKAGE) ?: return
        Demand.onRequest(context, pkg, intent.getBooleanExtra(Demand.EXTRA_NEED, false))
    }
}
