package mk.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0B0D12)
private val Card = Color(0xFF1A1E27)
private val Gold = Color(0xFFFFC21A)
private val Dim = Color(0xFF9AA3B2)
private val Ok = Color(0xFF3DDC84)

class MainActivity : ComponentActivity() {
    private var resumed by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SetupScreen(resumed) }
    }

    override fun onResume() {
        super.onResume()
        resumed++
    }
}

private data class Setup(
    val installed: Boolean = false,
    val controlAllowed: Boolean = false,
    val vpnAllowed: Boolean = false,
    val watching: Boolean = false,
)

private class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Setup checklist and tests on the left, apps and their regions on the right; pickers open over both. */
@Composable
private fun SetupScreen(resumed: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf(Setup()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val status by Vpn.status.collectAsState()
    var current by remember { mutableStateOf<Region?>(null) }
    var login by remember { mutableStateOf(Login.get(context)) }
    var editingLogin by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<AppEntry?>(null) }
    // Bumped whenever an assignment changes, so the test list and app rows re-read them
    var assignmentsTick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshTick++ }

    LaunchedEffect(resumed, refreshTick) {
        val installed = Vpn.installed(context)
        val control = installed && Vpn.canControl(context)
        setup = Setup(
            installed = installed,
            controlAllowed = control,
            vpnAllowed = control && Vpn.vpnPermissionIntent(context) == null,
            watching = SwitchService.enabled(context),
        )
    }
    LaunchedEffect(status, setup) { current = if (setup.controlAllowed) Vpn.current(context) else null }

    val firstFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    val rowFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    LaunchedEffect(Unit) { delay(150); runCatching { firstFocus.requestFocus() } }
    val ready = setup.controlAllowed && setup.vpnAllowed && login != null
    val used = remember(assignmentsTick) { Assignments.usedRegions(context) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            LazyColumn(Modifier.width(470.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Text("VPN Switcher", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.size(6.dp))
                    Text(statusLine(status, current), color = if (status.connected) Ok else Dim, fontSize = 16.sp)
                    Spacer(Modifier.size(12.dp))
                }
                item {
                    Step(
                        "1. Install “OpenVPN for Android”", setup.installed,
                        if (setup.installed) "Installed" else "The free app by Arne Schwabe",
                        Modifier.focusRequester(firstFocus),
                    ) {
                        if (!setup.installed) runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Vpn.PACKAGE}")))
                        }
                    }
                }
                item {
                    Step("2. Allow it to be controlled", setup.controlAllowed, "Tick “I trust this application”, then OK", enabled = setup.installed) {
                        scope.launch { Vpn.permissionIntent(context)?.let(launcher::launch) ?: refreshTick++ }
                    }
                }
                item {
                    Step("3. Allow VPN connections", setup.vpnAllowed, "Android asks once for OpenVPN", enabled = setup.controlAllowed) {
                        scope.launch { Vpn.vpnPermissionIntent(context)?.let(launcher::launch) ?: refreshTick++ }
                    }
                }
                item {
                    Step("4. PIA login", login != null, login?.let { "Signed in as ${it.user}" } ?: "Your PIA username and password", Modifier.focusRequester(loginFocus)) {
                        editingLogin = true
                    }
                }
                item {
                    Step("5. Turn on in Accessibility", setup.watching, "Settings → Accessibility → VPN Switcher") {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                if (used.isNotEmpty()) item {
                    Spacer(Modifier.size(10.dp))
                    Text("Test", color = Dim, fontSize = 14.sp)
                }
                items(used, key = { it.id }) { region ->
                    Step("Connect ${region.name}", current == region && status.connected, null, enabled = ready) {
                        scope.launch { Vpn.profileFor(context, region)?.let { context.startActivity(Vpn.launchIntent(it)) } }
                    }
                }
                item {
                    Step("Disconnect", false, null, enabled = setup.controlAllowed) { scope.launch { Vpn.disconnect(context) } }
                }
            }
            AppsColumn(Modifier.weight(1f), assignmentsTick, rowFocus) { picking = it }
        }

        picking?.let { app ->
            RegionPicker(
                app = app,
                used = used,
                onPick = { target ->
                    Assignments.set(context, app.pkg, target)
                    assignmentsTick++
                    picking = null
                    scope.launch { delay(80); runCatching { rowFocus[app.pkg]?.requestFocus() } }
                },
                onClose = {
                    picking = null
                    scope.launch { delay(80); runCatching { rowFocus[app.pkg]?.requestFocus() } }
                },
            )
        }
        if (editingLogin) {
            LoginForm(
                initial = login,
                onSave = { creds ->
                    Login.set(context, creds)
                    login = creds
                    editingLogin = false
                    // Profiles carry the login: drop them so they're recreated with the new one when next used
                    scope.launch { Vpn.removeProfiles(context); delay(80); runCatching { loginFocus.requestFocus() } }
                },
                onClose = { editingLogin = false; scope.launch { delay(80); runCatching { loginFocus.requestFocus() } } },
            )
        }
    }
}

@Composable
private fun AppsColumn(modifier: Modifier, assignmentsTick: Int, rowFocus: MutableMap<String, FocusRequester>, onPick: (AppEntry) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) { value = withContext(Dispatchers.IO) { launchableApps(context) } }

    Column(modifier.fillMaxHeight()) {
        Text("Apps", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("OK picks the VPN region an app gets when you open it.", color = Dim, fontSize = 14.sp)
        Spacer(Modifier.size(12.dp))
        val list = apps
        if (list == null) {
            Text("Loading…", color = Dim)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(list, key = { it.pkg }) { app ->
                    val target = remember(assignmentsTick) { Assignments.get(context, app.pkg) }
                    val focus = rowFocus.getOrPut(app.pkg) { FocusRequester() }
                    Tile(onClick = { onPick(app) }, modifier = Modifier.fillMaxWidth().focusRequester(focus)) { focused ->
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (app.icon != null) Image(app.icon, null, Modifier.size(36.dp)) else Box(Modifier.size(36.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(
                                app.label, color = if (focused) Color.Black else Color.White, fontSize = 18.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(
                                targetLabel(target),
                                color = if (focused) Color.Black else if (target is Target.To) Gold else Dim, fontSize = 18.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun targetLabel(target: Target) = when (target) {
    Target.Leave -> "Leave as is"
    Target.Off -> "VPN off"
    is Target.To -> target.region.name
}

/** Choices for one app: leave / off, regions already in use, then every country (cities open a second list). */
@Composable
private fun RegionPicker(app: AppEntry, used: List<Region>, onPick: (Target) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val currentTarget = remember { Assignments.get(context, app.pkg) }
    val byCountry = remember { Regions.all(context).groupBy { it.country } }
    var country by remember { mutableStateOf<String?>(null) }
    // Where to land when coming back out of a country's list
    var returnTo by remember { mutableStateOf<String?>(null) }

    data class Choice(val section: String?, val label: String, val target: Target?, val country: String? = null)
    val choices = remember(country) {
        val c = country
        if (c != null) byCountry.getValue(c).map { Choice(null, it.city ?: it.name, Target.To(it)) }
        else buildList {
            add(Choice(null, "Leave the VPN as it is", Target.Leave))
            add(Choice(null, "Turn the VPN off", Target.Off))
            used.forEach { add(Choice("In use", it.name, Target.To(it))) }
            byCountry.forEach { (name, regions) ->
                add(if (regions.size == 1) Choice("All countries", name, Target.To(regions[0])) else Choice("All countries", "$name  ›", null, name))
            }
        }
    }
    val selectedCountry = (currentTarget as? Target.To)?.region?.country
    // Land on the app's current choice (or the country just left)
    val start = choices.indexOfFirst { ch ->
        if (returnTo != null) ch.country == returnTo else ch.target == currentTarget || (ch.country != null && ch.country == selectedCountry)
    }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    LaunchedEffect(country) { listState.scrollToItem((start - 3).coerceAtLeast(0)) }
    val focus = remember(country) { FocusRequester() }
    LaunchedEffect(country) { delay(100); runCatching { focus.requestFocus() } }
    BackHandler { if (country != null) { returnTo = country; country = null } else onClose() }

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxHeight().width(620.dp).background(Bg).padding(horizontal = 32.dp, vertical = 28.dp)) {
            Text(app.label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(country?.let { "$it – pick a location (Back for all countries)" } ?: "When this app opens:", color = Dim, fontSize = 15.sp)
            Spacer(Modifier.size(14.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                choices.forEachIndexed { i, ch ->
                    if (ch.section != null && choices.getOrNull(i - 1)?.section != ch.section) item(key = "h-${ch.section}") {
                        Text(ch.section, color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    }
                    item(key = "c-$country-$i") {
                        val selected = ch.target == currentTarget || (ch.country != null && ch.country == selectedCountry)
                        Tile(
                            onClick = { if (ch.target != null) onPick(ch.target) else country = ch.country },
                            modifier = Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(focus) else Modifier),
                        ) { focused ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(ch.label, color = if (focused) Color.Black else Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                if (selected) Text("✓", color = if (focused) Color.Black else Ok, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginForm(initial: Login.Credentials?, onSave: (Login.Credentials) -> Unit, onClose: () -> Unit) {
    var user by remember { mutableStateOf(initial?.user.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(Bg, RoundedCornerShape(16.dp)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PIA login", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("The username (p1234567) and password of your Private Internet Access account. Saved on this TV only.", color = Dim, fontSize = 15.sp)
            Field("Username", user, { user = it }, Modifier.focusRequester(focus), password = false)
            Field("Password", password, { password = it }, Modifier, password = true)
            Spacer(Modifier.size(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    onClick = { onSave(Login.Credentials(user.trim(), password)) },
                    modifier = Modifier.weight(1f),
                    enabled = user.isNotBlank() && password.isNotEmpty() && '\n' !in password,
                ) { focused ->
                    Text("Save", color = if (focused) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp))
                }
                Tile(onClick = onClose, modifier = Modifier.weight(1f)) { focused ->
                    Text("Cancel", color = if (focused) Color.Black else Color.White, fontSize = 18.sp, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, password: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Dim, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Gold),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
                imeAction = if (password) ImeAction.Done else ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            modifier = modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(Card, RoundedCornerShape(10.dp))
                .border(2.dp, if (focused) Gold else Card, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private fun statusLine(s: Vpn.Status, current: Region?): String = when {
    s.connected && current != null -> "Connected: ${current.name}"
    s.connected -> "Connected (another profile)"
    s.stopped -> "VPN off"
    else -> "${current?.let { "${it.name}: " } ?: ""}${s.state.lowercase().replace('_', ' ')}…"
}

private fun launchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val found = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
    }
    return found.map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName && it.packageName != Vpn.PACKAGE }
        .map { info ->
            val icon = runCatching { pm.getApplicationIcon(info).toBitmap(72, 72).asImageBitmap() }.getOrNull()
            AppEntry(info.packageName, pm.getApplicationLabel(info).toString(), icon)
        }
        // Apps with a choice first, then alphabetical
        .sortedWith(compareBy({ Assignments.get(context, it.pkg) == Target.Leave }, { it.label.lowercase() }))
}

@Composable
private fun Step(title: String, done: Boolean, detail: String?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Tile(onClick, modifier.fillMaxWidth(), enabled) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (focused) Color.Black else if (enabled) Color.White else Dim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (detail != null) Text(detail, color = if (focused) Color(0xFF333333) else Dim, fontSize = 13.sp)
            }
            if (done) Text("✓", color = if (focused) Color.Black else Ok, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Tile(onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true, content: @Composable (focused: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Card, focusedContainerColor = Gold, disabledContainerColor = Card.copy(alpha = 0.5f)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) { content(focused) }
}
