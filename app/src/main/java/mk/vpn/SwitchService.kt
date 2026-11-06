package mk.vpn

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sees which app comes to the front and, when its [Target] needs a different VPN state, covers it with
 * [HoldActivity], which switches the VPN and then reopens the app on the new connection.
 */
class SwitchService : AccessibilityService() {
    private val scope = MainScope()
    private var job: Job? = null
    private var lastPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Our own screens (setup, hold) don't count as leaving the app being switched for
        if (pkg == packageName || pkg == lastPackage) return
        lastPackage = pkg
        val target = Assignments.get(this, pkg)
        if (target == Target.Leave || HoldActivity.recentlyOpened(pkg)) return
        job?.cancel()
        job = scope.launch {
            val current = Vpn.current(this@SwitchService)
            val needed = when (target) {
                Target.Leave -> false
                Target.Off -> current != null
                is Target.To -> current != target.region
            }
            if (needed) HoldActivity.start(this@SwitchService, pkg)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, SwitchService::class.java)
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
