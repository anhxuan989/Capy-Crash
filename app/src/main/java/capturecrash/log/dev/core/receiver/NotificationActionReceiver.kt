package capturecrash.log.dev.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import capturecrash.log.dev.R
import capturecrash.log.dev.core.db.createDatabaseInstance
import capturecrash.log.dev.core.db.selectById
import capturecrash.log.dev.util.Intents
import capturecrash.log.dev.util.copyTextToClipboard
import capturecrash.log.dev.util.displayToast

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val crashId = intent?.getLongExtra("crash_id", -1L) ?: -1L
        val stackTrace = if (crashId > 0L) {
            createDatabaseInstance(context, "main")
            selectById(crashId)?.stackTrace
        } else {
            intent?.getStringExtra("stackTrace")
        }
        val pkg = intent?.getStringExtra("pkg")
        when (intent?.action) {
            Intents.INTENT_ACTION_COPY -> {
                context.copyTextToClipboard(R.string.copy_label, pkg, stackTrace)
                context.displayToast(R.string.copied_toast)
            }
        }
    }
}
