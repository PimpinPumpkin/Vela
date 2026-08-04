package app.vela.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The "call it just Maps" option (issue #226): the launcher entry lives on two manifest
 * activity-aliases and this swaps which one is enabled. Component state persists across reboots
 * and updates on its own, so there is no pref to keep in sync. DONT_KILL_APP because this runs
 * from inside Settings; launchers pick the change up within a moment.
 */
object AppNameAlias {
    private const val DEFAULT = "app.vela.Launcher"
    private const val GENERIC = "app.vela.LauncherMaps"

    fun isGeneric(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, GENERIC)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setGeneric(context: Context, generic: Boolean) {
        val pm = context.packageManager
        fun set(cls: String, enabled: Boolean) = pm.setComponentEnabledSetting(
            ComponentName(context, cls),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        // Enable the new face BEFORE disabling the old one so the app never has zero launcher
        // entries if the process dies between the two calls.
        if (generic) {
            set(GENERIC, true)
            set(DEFAULT, false)
        } else {
            set(DEFAULT, true)
            set(GENERIC, false)
        }
    }
}
