package capturecrash.log.dev.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import capturecrash.log.dev.util.PreferenceHelper
import capturecrash.log.dev.util.initScoopService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED)
            return

        if (PreferenceHelper.autostartOnBoot) {
            context.initScoopService()

            Log.d("CapyCrash", "Started application on boot")
        }
    }
}
