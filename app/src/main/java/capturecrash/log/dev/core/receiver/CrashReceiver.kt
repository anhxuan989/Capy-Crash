package capturecrash.log.dev.core.receiver

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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

class CrashReceiver : BroadcastReceiver() {

    private var packageName: String? = null
    private var description: String? = null
    private var stackTrace: String? = null

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intents.INTENT_ACTION)
            return

        description = intent.getStringExtra(Intents.INTENT_DESCRIPTION) ?: return
        if (description!!.startsWith(ThreadDeath::class.java.name) && ignoreThreadDeath)
            return

        packageName = intent.getStringExtra(Intents.INTENT_PACKAGE_NAME)
        val time = intent.getLongExtra(Intents.INTENT_TIME, System.currentTimeMillis())
        stackTrace = intent.getStringExtra(Intents.INTENT_STACKTRACE)

        if (blacklistList.contains(packageName)) {
            return
        }

        val crash: Crash?
        val update = intent.getBooleanExtra(Intents.INTENT_UPDATE, false)
        if (!update) {
            crash = Crash(time, packageName, description, stackTrace)
            updateCrashDatabase(context, crash)
            MainActivity.requestUpdate(crash)
        } else {
            crash = IntentCompat.getParcelableExtra(intent, "crash", Crash::class.java)
        }

        if (!showNotifications || mutedList.contains(packageName)) {
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

        val clickIntent = Intent(context, DetailActivity::class.java).apply {
            if (crash != null && crash.id > 0L) {
                putExtra(DetailActivity.EXTRA_CRASH_ID, crash.id)
            } else {
                putExtra(DetailActivity.EXTRA_CRASH, crash)
            }
        }
        val stackBuilder = TaskStackBuilder.create(context)
            .addParentStack(DetailActivity::class.java)
            .addNextIntent(clickIntent)
        val clickPendingIntent = stackBuilder.getPendingIntent(0, setPendingIntentFlag())

        val builder = NotificationCompat.Builder(context, "crashes").apply {
            setSmallIcon(R.drawable.ic_bug_report)
            setLargeIcon(CrashLoader.getAppIcon(context, packageName).convertToBitmap())
            setContentTitle(CrashLoader.getAppName(context, packageName, false))
            setContentText(description)
            setPriority(NotificationCompat.PRIORITY_MAX)
            setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            setColor(context.getCompatColor(R.color.colorAccent))
            setAutoCancel(true)
            setOnlyAlertOnce(true)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            setGroup("crashes")
            setContentIntent(clickPendingIntent)
            setNotificationStyle()
        }

        val notificationId = (time - bootTime).toInt()
        if (showActionButtons) {
            builder.addActionButtons(context, notificationId, crash?.id ?: -1L)
        }

        val manager = NotificationManagerCompat.from(context)
        manager.notify(notificationId, builder.build())
    }

    private fun updateCrashDatabase(context: Context, crash: Crash?) {
        createDatabaseInstance(context, "receiver")
        insertValues(listOf(crash))
        destroyDatabaseInstance("receiver")
    }

    private fun NotificationCompat.Builder.setNotificationStyle(): NotificationCompat.Builder {
        return if (showStackTraceNotifications && stackTrace != null) {
            val style = NotificationCompat.InboxStyle()
            val traces = stackTrace!!.split("\n".toRegex()).toTypedArray()
            var i = 0
            while (i < 6 && i < traces.size) {
                // Inbox style only shows 6 entries
                style.addLine(traces[i])
                i++
            }
            setStyle(style)
        } else {
            setStyle(NotificationCompat.BigTextStyle().bigText(description))
        }
    }

    private fun NotificationCompat.Builder.addActionButtons(
        context: Context, notificationId: Int, crashId: Long
    ): NotificationCompat.Builder {
        val copyIntent = Intent(context, NotificationActionReceiver::class.java)
            .putExtra("crash_id", crashId)
            .putExtra("pkg", packageName)
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

        val safeTrace = if ((stackTrace?.length ?: 0) > 65536) {
            stackTrace!!.substring(0, 65536) + "\n\n... [Truncated]"
        } else {
            stackTrace
        }
        val shareIntent = Intent(Intent.ACTION_SEND)
            .putExtra("pkg", packageName)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, safeTrace)
        val shareChooserIntent = Intent.createChooser(shareIntent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val sharePendingIntent = PendingIntent.getActivity(
            context,
            notificationId, shareChooserIntent, setPendingIntentFlag()
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
}
