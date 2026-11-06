package capturecrash.log.dev.core.service.detector

import android.content.Context
import android.content.Intent
import android.os.IBinder
import capturecrash.log.dev.core.service.indicator.IndicatorService

class CrashDetectorService : IndicatorService() {

    private lateinit var impl: CrashDetector

    override fun onCreate() {
        super.onCreate()

        impl = Impl(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        impl.kill()
    }

    override fun onBind(intent: Intent): IBinder {
        return impl
    }

    class Impl(private val context: Context) : CrashDetector() {

        override fun sendBroadcast(intent: Intent) {
            context.sendBroadcast(intent)
        }
    }
}
