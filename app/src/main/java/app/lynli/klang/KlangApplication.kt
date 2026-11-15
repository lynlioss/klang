package app.lynli.klang

import android.app.Application
import android.content.Context
import app.lynli.klang.analytics.KlangAnalytics

class KlangApplication : Application() {
    internal lateinit var analytics: KlangAnalytics
        private set

    override fun onCreate() {
        super.onCreate()
        analytics = KlangAnalytics(this)
        // The chosen launcher entry lives in package manager state, which can
        // drift from the preference after backups or package updates.
        val stored = getSharedPreferences("klang", Context.MODE_PRIVATE).getString("appIcon", null)
        AppIconSwitcher.apply(this, AppIcon.from(stored))
    }
}
