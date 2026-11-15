package app.lynli.klang

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher icon variants. Each one is an enabled/disabled activity-alias in the
 * manifest, because Android only resolves the home screen icon at install time.
 */
enum class AppIcon(val storageValue: String, val component: String) {
    Light("light", "app.lynli.klang.LauncherLight"),
    Dark("dark", "app.lynli.klang.LauncherDark");

    companion object {
        fun from(value: String?): AppIcon = entries.firstOrNull { it.storageValue == value } ?: Light
    }
}

object AppIconSwitcher {
    /** Enables the chosen launcher entry and retires the other one in place. */
    fun apply(context: Context, icon: AppIcon) {
        val manager = context.packageManager
        val chosen = ComponentName(context, icon.component)
        if (manager.getComponentEnabledSetting(chosen) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            manager.setComponentEnabledSetting(chosen, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)
        }
        AppIcon.entries.filterNot { it == icon }.forEach { other ->
            val component = ComponentName(context, other.component)
            if (manager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                manager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
            }
        }
    }
}
