package app.vela.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The "call it just Maps" option (issue #226): the launcher entry lives on two manifest
 * activity-aliases and this swaps which one is enabled. The "maps app" entry (Android Automotive's
 * map pane, "open maps" on a phone) is a second pair that follows the same name. Component state
 * persists across reboots and updates on its own, so there is no pref to keep in sync.
 * DONT_KILL_APP because this runs from inside Settings; launchers pick the change up within a
 * moment.
 */
object AppNameAlias {
    private const val DEFAULT = "app.vela.Launcher"
    private const val GENERIC = "app.vela.LauncherMaps"
    private const val PANEL = "app.vela.MapsPanel"
    private const val PANEL_GENERIC = "app.vela.MapsPanelGeneric"

    fun isGeneric(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, GENERIC)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setGeneric(context: Context, generic: Boolean) {
        // Enable the new face BEFORE disabling the old one so the app never has zero launcher
        // entries if the process dies between the two calls.
        if (generic) {
            set(context, GENERIC, true)
            set(context, DEFAULT, false)
        } else {
            set(context, DEFAULT, true)
            set(context, GENERIC, false)
        }
        syncPanel(context, generic)
    }

    /** Puts the "maps app" pair on the launcher's name. Run at start too: an install that took
     *  the generic name before the pair existed still has the default one enabled. */
    fun syncPanel(context: Context, generic: Boolean = isGeneric(context)) {
        val on = if (generic) PANEL_GENERIC else PANEL
        val off = if (generic) PANEL else PANEL_GENERIC
        if (!enabled(context, on)) set(context, on, true)
        if (enabled(context, off)) set(context, off, false)
    }

    /** A component counts as enabled when its state is ENABLED, or DEFAULT and the manifest says so. */
    private fun enabled(context: Context, cls: String): Boolean =
        when (context.packageManager.getComponentEnabledSetting(ComponentName(context, cls))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> cls == PANEL || cls == DEFAULT
            else -> false
        }

    private fun set(context: Context, cls: String, enabled: Boolean) =
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, cls),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
}
