package mk.vpn

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sees which app comes to the front and applies its [Target]: connect its PIA region, turn the VPN
 * off, or leave it alone.
 */
class SwitchService : AccessibilityService() {
    private val scope = MainScope()
    private var job: Job? = null
    private var lastPackage: String? = null
    private var wanted: Target? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastPackage) return
        lastPackage = pkg
        val target = Assignments.get(this, pkg)
        if (target == Target.Leave) return
        // A quick hop through another window (keyboard, volume) shouldn't restart a switch in progress
        if (target == wanted && job?.isActive == true) return
        wanted = target
        job?.cancel()
        job = scope.launch {
            delay(300)
            apply(target)
        }
    }

    private suspend fun apply(target: Target) {
        val current = Vpn.current(this)
        when (target) {
            Target.Leave -> Unit
            Target.Off -> if (current != null) {
                Vpn.disconnect(this)
                toast("VPN off")
            }
            is Target.To -> if (current != target.region) connect(target.region)
        }
    }

    private suspend fun connect(region: Region) {
        toast("VPN: ${region.name}…")
        val uuid = Vpn.start(this, region)
        if (uuid == null) {
            toast("VPN Switcher isn't set up yet – open it")
            return
        }
        // OpenVPN can't show its login prompt from the background; if it didn't get going, open it from here
        if (!Vpn.awaitStarted(uuid, 2_500)) runCatching { startActivity(Vpn.launchIntent(uuid)) }
        toast(if (Vpn.awaitConnected(uuid)) "VPN connected: ${region.name}" else "VPN didn't connect: ${region.name}")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
