package mk.vpn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Covers an app that just opened while the VPN switches to its region, then reopens the app from its
 * start screen so it loads (and checks where you are) over the new connection.
 */
class HoldActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val target = Assignments.get(this, pkg)
        if (target == Target.Leave) return finish()
        val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val label = info?.let { packageManager.getApplicationLabel(it).toString() } ?: pkg
        // The app's own TV banner (or icon), so the wait looks like the app loading rather than another app
        val art = info?.let { runCatching { packageManager.getApplicationBanner(it) ?: packageManager.getApplicationIcon(it) }.getOrNull() }
            ?.let { d -> runCatching { d.toBitmap(d.intrinsicWidth.coerceIn(1, 640), d.intrinsicHeight.coerceIn(1, 360)).asImageBitmap() }.getOrNull() }
        setContent { HoldScreen(label, art, target, onOpen = { reopen(pkg) }, onCancel = ::goHome, onFailed = ::comeBack) }
    }

    /** OpenVPN's own screens can end up over us; on failure the choices must be in front. */
    private fun comeBack() {
        runCatching { startActivity(Intent(this, HoldActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
    }

    /** Fresh start of the app's launcher screen: CLEAR_TASK finishes what it had loaded before the switch. */
    private fun reopen(pkg: String) {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            justOpened(pkg)
            runCatching {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
        finish()
        noTransition()
    }

    @Suppress("DEPRECATION")
    private fun noTransition() = overridePendingTransition(0, 0)

    private fun goHome() {
        runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private var opened: Pair<String, Long>? = null

        fun start(context: Context, pkg: String) {
            context.startActivity(
                Intent(context, HoldActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }

        private fun justOpened(pkg: String) {
            opened = pkg to SystemClock.elapsedRealtime()
        }

        /** True right after we reopened [pkg]: its window appearing again must not start another hold. */
        fun recentlyOpened(pkg: String) =
            opened?.let { (p, at) -> p == pkg && SystemClock.elapsedRealtime() - at < 10_000 } == true
    }
}

private sealed interface Phase {
    data object Working : Phase
    data object Failed : Phase
}

@Composable
private fun HoldScreen(label: String, art: ImageBitmap?, target: Target, onOpen: () -> Unit, onCancel: () -> Unit, onFailed: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var phase by remember { mutableStateOf<Phase>(Phase.Working) }
    var attempt by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onCancel)

    LaunchedEffect(attempt) {
        phase = Phase.Working
        val ok = when (target) {
            Target.Leave -> true
            Target.Off -> {
                Vpn.disconnect(context)
                withTimeoutOrNull(5_000) { Vpn.status.first { it.stopped } } != null
            }
            is Target.To -> connect(context, target.region)
        }
        if (ok) {
            // A moment for routes and DNS to settle on the new connection before the app starts loading
            delay(400)
            onOpen()
        } else {
            phase = Phase.Failed
            onFailed()
        }
    }

    val what = when (target) {
        Target.Leave -> ""
        Target.Off -> "Turning the VPN off"
        is Target.To -> "Connecting to ${target.region.name}"
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (art != null) Image(art, label, Modifier.height(150.dp).clip(RoundedCornerShape(14.dp)))
            else Text(label, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            when (phase) {
                Phase.Working -> {
                    Spacer(Modifier.size(10.dp))
                    ProgressBar()
                    Text(what, color = Color(0xFF9AA3B2), fontSize = 16.sp)
                }
                Phase.Failed -> {
                    Spacer(Modifier.size(10.dp))
                    Text("The VPN didn't connect", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (target is Target.To) "${target.region.name} – check the PIA login in VPN Switcher, or try again." else "",
                        color = Color(0xFF9AA3B2), fontSize = 16.sp,
                    )
                    Spacer(Modifier.size(8.dp))
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HoldButton("Try again", Modifier.focusRequester(focus)) { attempt++ }
                        HoldButton("Open anyway", Modifier, onOpen)
                    }
                }
            }
        }
    }
}

/** Starts [region] and waits for it; opens OpenVPN's own screen if it needs input (it can't from the background). */
private suspend fun connect(context: Context, region: Region): Boolean {
    if (Vpn.current(context) == region && Vpn.status.value.connected) return true
    val uuid = Vpn.start(context, region) ?: return false
    if (!Vpn.awaitStarted(uuid, 2_500)) runCatching { context.startActivity(Vpn.launchIntent(uuid)) }
    return Vpn.awaitConnected(uuid)
}

/** Thin indeterminate bar, like a TV app's loading screen. */
@Composable
private fun ProgressBar() {
    val t by rememberInfiniteTransition(label = "progress").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "offset",
    )
    Box(Modifier.width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF1A1E27))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(0.35f)
                .graphicsLayer { translationX = (t * 1.35f - 0.35f) * 260.dp.toPx() }
                .background(Color(0xFFFFC21A), RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun HoldButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.width(220.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1A1E27), focusedContainerColor = Color(0xFFFFC21A)),
    ) {
        Text(
            text, color = if (focused) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}
