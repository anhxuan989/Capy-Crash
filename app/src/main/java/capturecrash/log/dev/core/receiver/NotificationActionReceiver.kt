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
            Intents.INTENT_ACTION_SHARE -> {
                if (!stackTrace.isNullOrEmpty()) {
                    val safeTrace = if (stackTrace.length > 131072) {
                        stackTrace.substring(0, 131072) + "\n\n... [Truncated]"
                    } else {
                        stackTrace
                    }
                    val shareIntent = Intent(Intent.ACTION_SEND)
                        .putExtra("pkg", pkg)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, safeTrace)
                    val chooser = Intent.createChooser(shareIntent, context.getString(R.string.action_share))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                }
            }
        }
    }
}
