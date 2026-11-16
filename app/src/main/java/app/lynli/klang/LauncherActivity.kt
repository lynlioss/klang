package app.lynli.klang

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * One-shot launcher entry used by fresh installs. It starts the real UI in the
 * app's normal task, then MainActivity retires this component so only the chosen
 * icon alias remains in the launcher.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
