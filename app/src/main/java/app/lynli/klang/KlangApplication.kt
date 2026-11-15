package app.lynli.klang

import android.app.Application
import app.lynli.klang.analytics.KlangAnalytics

class KlangApplication : Application() {
    internal lateinit var analytics: KlangAnalytics
        private set

    override fun onCreate() {
        super.onCreate()
        analytics = KlangAnalytics(this)
    }
}
