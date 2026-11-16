package app.lynli.klang

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes

/**
 * Launcher icon variants. Each one is an enabled/disabled activity-alias in the
 * manifest, because Android only resolves the home screen icon at install time.
 *
 * Adding a variant means adding an entry here, a matching alias with the same
 * component name in AndroidManifest.xml, and a preview drawable.
 */
enum class AppIcon(
    val storageValue: String,
    val component: String,
    val label: String,
    @DrawableRes val preview: Int,
) {
    Light("light", "app.lynli.klang.LauncherLight", "Light", R.drawable.app_icon_light),
    Dark("dark", "app.lynli.klang.LauncherDark", "Dark", R.drawable.app_icon_dark);

    companion object {
        fun from(value: String?): AppIcon = entries.firstOrNull { it.storageValue == value } ?: Light
    }
}

object AppIconSwitcher {
    /**
     * The install-time launcher entry. Fresh installs need one enabled launcher
     * component, but it must not be one of the selectable aliases: disabling the
     * alias that launched the current task makes Android remove that task.
     *
     * It hands off to MainActivity in its own task, and MainActivity retires it
     * once the real task is on screen.
     */
    private const val LAUNCHER = "app.lynli.klang.LauncherActivity"

    /**
     * Enables the chosen launcher entry and retires the rest. Selectable aliases
     * are disabled by default in the manifest, so resetting them to the default
     * state hides them without the task removal that an explicit disable causes.
     */
    fun apply(context: Context, icon: AppIcon) {
        val manager = context.packageManager
        enable(manager, ComponentName(context, icon.component))
        AppIcon.entries.filterNot { it == icon }.forEach { other ->
            resetToManifest(manager, ComponentName(context, other.component))
        }
    }

    /** Removes the one-time install entry after MainActivity is running. */
    fun retireLauncher(context: Context) {
        val manager = context.packageManager
        val launcher = ComponentName(context, LAUNCHER)
        if (manager.getComponentEnabledSetting(launcher) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            manager.setComponentEnabledSetting(launcher, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
    }

    private fun enable(manager: PackageManager, component: ComponentName) {
        if (manager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            manager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)
        }
    }

    private fun resetToManifest(manager: PackageManager, component: ComponentName) {
        if (manager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            manager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP)
        }
    }
}
