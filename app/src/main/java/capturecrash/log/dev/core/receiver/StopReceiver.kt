package capturecrash.log.dev.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import capturecrash.log.dev.util.stopScoopService

class StopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        context.stopScoopService()
    }
}
