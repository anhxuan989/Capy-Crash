package capturecrash.log.dev.core.receiver

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import capturecrash.log.dev.BuildConfig
import capturecrash.log.dev.R
import capturecrash.log.dev.ScoopApplication.Companion.bootTime
import capturecrash.log.dev.core.data.crash.Crash
import capturecrash.log.dev.core.data.crash.CrashLoader
import capturecrash.log.dev.core.db.createDatabaseInstance
import capturecrash.log.dev.core.db.destroyDatabaseInstance
import capturecrash.log.dev.core.db.insertValues
import capturecrash.log.dev.ui.activity.DetailActivity
import capturecrash.log.dev.ui.activity.MainActivity
import capturecrash.log.dev.util.Intents
import capturecrash.log.dev.util.PreferenceHelper.blacklistList
import capturecrash.log.dev.util.PreferenceHelper.ignoreThreadDeath
import capturecrash.log.dev.util.PreferenceHelper.mutedList
import capturecrash.log.dev.util.PreferenceHelper.showActionButtons
import capturecrash.log.dev.util.PreferenceHelper.showNotifications
import capturecrash.log.dev.util.PreferenceHelper.showStackTraceNotifications
import capturecrash.log.dev.util.convertToBitmap
import capturecrash.log.dev.util.getCompatColor
import capturecrash.log.dev.util.setPendingIntentFlag
import java.util.concurrent.Executors

class CrashReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intents.INTENT_ACTION)
            return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        receiverExecutor.execute {
            try {
                handleCrashIntent(appContext, intent)
            } catch (t: Throwable) {
                Log.e("CapyCrash", "Error handling crash broadcast", t)
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handleCrashIntent(context: Context, intent: Intent) {
        val rawDesc = intent.getStringExtra(Intents.INTENT_DESCRIPTION) ?: return
        if (rawDesc.startsWith(ThreadDeath::class.java.name) && ignoreThreadDeath)
            return

        val pkgName = intent.getStringExtra(Intents.INTENT_PACKAGE_NAME)
        if (pkgName.isNullOrEmpty() || pkgName == BuildConfig.APPLICATION_ID) {
            return
        }

        val time = intent.getLongExtra(Intents.INTENT_TIME, System.currentTimeMillis())
        val rawStackTrace = intent.getStringExtra(Intents.INTENT_STACKTRACE)

        if (blacklistList.contains(pkgName)) {
            return
        }

        val crash: Crash?
        val update = intent.getBooleanExtra(Intents.INTENT_UPDATE, false)
        if (!update) {
            crash = Crash(time, pkgName, rawDesc, rawStackTrace)
            updateCrashDatabase(context, crash)
            MainActivity.requestUpdate(crash)
        } else {
            crash = IntentCompat.getParcelableExtra(intent, "crash", Crash::class.java)
        }

        if (!showNotifications || mutedList.contains(pkgName)) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val crashId = crash?.id ?: -1L
        val clickIntent = Intent(context, DetailActivity::class.java).apply {
            if (crashId > 0L) {
                putExtra(DetailActivity.EXTRA_CRASH_ID, crashId)
            } else if (crash != null) {
                // Avoid putting huge stack traces inside PendingIntent sent to SystemUI
                val lightCrash = Crash(
                    crash.time,
                    crash.packageName,
                    crash.description,
                    truncateForBinder(crash.stackTrace, 16384)
                )
                putExtra(DetailActivity.EXTRA_CRASH, lightCrash)
            }
        }
        val stackBuilder = TaskStackBuilder.create(context)
            .addParentStack(DetailActivity::class.java)
            .addNextIntent(clickIntent)
        val notificationId = (time - bootTime).toInt()
        val clickPendingIntent = stackBuilder.getPendingIntent(notificationId, setPendingIntentFlag())

        val safeDesc = truncateForBinder(rawDesc, 512) ?: ""
        val builder = NotificationCompat.Builder(context, "crashes").apply {
            setSmallIcon(R.drawable.ic_bug_report)
            setLargeIcon(CrashLoader.getAppIcon(context, pkgName).convertToBitmap())
            setContentTitle(CrashLoader.getAppName(context, pkgName, false))
            setContentText(safeDesc)
            setPriority(NotificationCompat.PRIORITY_MAX)
            setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            setColor(context.getCompatColor(R.color.colorAccent))
            setAutoCancel(true)
            setOnlyAlertOnce(true)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            setGroup("crashes")
            setContentIntent(clickPendingIntent)
            setNotificationStyle(safeDesc, rawStackTrace)
        }

        if (showActionButtons) {
            builder.addActionButtons(context, notificationId, crashId, pkgName, rawStackTrace)
        }

        val manager = NotificationManagerCompat.from(context)
        manager.notify(notificationId, builder.build())
    }

    private fun updateCrashDatabase(context: Context, crash: Crash?) {
        createDatabaseInstance(context, "receiver")
        insertValues(listOf(crash))
        destroyDatabaseInstance("receiver")
    }

    private fun NotificationCompat.Builder.setNotificationStyle(
        description: String,
        stackTrace: String?
    ): NotificationCompat.Builder {
        return if (showStackTraceNotifications && stackTrace != null) {
            val style = NotificationCompat.InboxStyle()
            val traces = stackTrace.lineSequence().take(6).toList()
            for (line in traces) {
                style.addLine(truncateForBinder(line, 256))
            }
            setStyle(style)
        } else {
            setStyle(NotificationCompat.BigTextStyle().bigText(description))
        }
    }

    private fun NotificationCompat.Builder.addActionButtons(
        context: Context,
        notificationId: Int,
        crashId: Long,
        pkgName: String,
        stackTrace: String?
    ): NotificationCompat.Builder {
        val copyIntent = Intent(context, NotificationActionReceiver::class.java)
            .putExtra("crash_id", crashId)
            .putExtra("pkg", pkgName)
            .setAction(Intents.INTENT_ACTION_COPY)
        val copyPendingIntent = PendingIntent.getBroadcast(
            context, notificationId, copyIntent, setPendingIntentFlag()
        )
        addAction(
            NotificationCompat.Action(
                R.drawable.ic_content_copy,
                context.getString(R.string.action_copy_short),
                copyPendingIntent
            )
        )

        val safeTrace = truncateForBinder(stackTrace, 32768)
        val shareIntent = Intent(Intent.ACTION_SEND)
            .putExtra("pkg", pkgName)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, safeTrace)
        val shareChooserIntent = Intent.createChooser(shareIntent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val sharePendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 1,
            shareChooserIntent,
            setPendingIntentFlag()
        )
        addAction(
            NotificationCompat.Action(
                R.drawable.ic_share,
                context.getString(R.string.action_share),
                sharePendingIntent
            )
        )
        return this
    }

    private fun truncateForBinder(text: String?, maxLen: Int): String? {
        if (text == null) return null
        return if (text.length > maxLen) {
            text.substring(0, maxLen) + "\n\n... [Truncated]"
        } else {
            text
        }
    }

    companion object {
        private val receiverExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "CapyCrash-ReceiverWorker").apply { isDaemon = true }
        }
    }
}
