package mk.vpn

import android.content.Context

/** A PIA OpenVPN region; [id] is the name of PIA's official .ovpn file, [host] its server. */
data class Region(val id: String, val name: String, val host: String) {
    /** "United States" for "United States – New York". */
    val country get() = name.substringBefore(" – ")

    /** "New York" for "United States – New York"; null for single-location countries. */
    val city get() = name.substringAfter(" – ", "").ifEmpty { null }

    /** Name of the profile in OpenVPN for Android; also how we find it again. */
    val profileName get() = "$PROFILE_PREFIX$name"

    /** PIA's profile (UDP 1198) for this region, with the saved PIA login embedded when there is one. */
    fun config(context: Context, login: Login.Credentials?): String {
        val template = context.assets.open("pia.ovpn").bufferedReader().use { it.readText() }.replace("%HOST%", host)
        if (login == null) return template
        return template.replace(Regex("(?m)^auth-user-pass\\s*$"), "<auth-user-pass>\n${login.user}\n${login.password}\n</auth-user-pass>")
    }

    companion object {
        const val PROFILE_PREFIX = "PIA – "
    }
}

object Regions {
    @Volatile private var cache: List<Region>? = null

    /** Every PIA region, alphabetical (from assets/regions.tsv, generated from PIA's openvpn.zip). */
    fun all(context: Context): List<Region> = cache ?: context.assets.open("regions.tsv").bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() }.map { val (id, name, host) = it.split('\t'); Region(id, name, host) }.toList()
    }.also { cache = it }

    fun get(context: Context, id: String) = all(context).firstOrNull { it.id == id }

    fun byProfileName(context: Context, name: String) = all(context).firstOrNull { it.profileName == name }
}

/** What happens when an app comes to the front. */
sealed interface Target {
    /** Nothing: the VPN stays as it is. */
    data object Leave : Target
    /** Disconnect the VPN. */
    data object Off : Target
    data class To(val region: Region) : Target
}

/** Each app's [Target], by package name. */
object Assignments {
    private const val FILE = "assignments"
    private const val OFF = "off"
    private val defaults = mapOf(
        "mk.tv" to "north_macedonia",
        "hr.hrt.hrti" to "croatia",
        "com.morescreens.atv.rts" to "serbia",
        "com.stremio.one" to "us_west",
    )

    fun get(context: Context, pkg: String): Target {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val value = if (prefs.contains(pkg)) prefs.getString(pkg, null) else defaults[pkg]
        return when {
            value.isNullOrEmpty() -> Target.Leave
            value == OFF -> Target.Off
            else -> Regions.get(context, value)?.let { Target.To(it) } ?: Target.Leave
        }
    }

    fun set(context: Context, pkg: String, target: Target) {
        val value = when (target) { Target.Leave -> ""; Target.Off -> OFF; is Target.To -> target.region.id }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(pkg, value).apply()
    }

    /** Regions some app uses, so they can be listed first and tested. */
    fun usedRegions(context: Context): List<Region> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val ids = (defaults.filterKeys { !prefs.contains(it) }.values + prefs.all.values.filterIsInstance<String>()).toSet()
        return Regions.all(context).filter { it.id in ids }
    }
}

/** The PIA login, kept in this app's private storage and written into each region's OpenVPN profile. */
object Login {
    data class Credentials(val user: String, val password: String)

    private const val FILE = "login"

    fun get(context: Context): Credentials? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val user = prefs.getString("user", null) ?: return null
        val password = prefs.getString("password", null) ?: return null
        return Credentials(user, password)
    }

    fun set(context: Context, credentials: Credentials) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("user", credentials.user).putString("password", credentials.password).apply()
    }
}
