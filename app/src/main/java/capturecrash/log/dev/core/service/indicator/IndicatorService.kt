package capturecrash.log.dev.core.service.indicator

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import capturecrash.log.dev.R
import capturecrash.log.dev.core.receiver.StopReceiver
import capturecrash.log.dev.util.getCompatColor
import capturecrash.log.dev.util.setPendingIntentFlag

open class IndicatorService : Service() {

    override fun onBind(intent: Intent): IBinder {
        TODO("unsupported.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startIndicatorForeground()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startIndicatorForeground()
    }

    private fun startIndicatorForeground() {
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, StopReceiver::class.java),
            setPendingIntentFlag()
        )
        val stopAction =
            NotificationCompat.Action(0, getString(R.string.action_kill), stopPendingIntent)

        val notification = NotificationCompat.Builder(this, "status")
            .setSmallIcon(R.drawable.ic_bug_report)
            .setContentTitle(getString(R.string.scoop_running))
            .setColor(getCompatColor(R.color.colorAccent))
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .addAction(stopAction)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(this, 100, notification, fgsType)
    }
}
