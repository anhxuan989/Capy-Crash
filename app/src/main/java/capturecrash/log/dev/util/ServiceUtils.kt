package capturecrash.log.dev.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import capturecrash.log.dev.core.service.detector.CrashDetectorService

@Volatile
var isServiceActive = false

fun Context.initScoopService(onResult: ((Boolean) -> Unit)? = null) {
    if (isServiceActive) {
        onResult?.invoke(true)
        return
    }
    val appContext = applicationContext
    val thread = HandlerThread("startCrashDetector")
    thread.start()
    Handler(thread.looper).post {
        val started = appContext.startScoopService()
        isServiceActive = started
        thread.quitSafely()
        if (onResult != null) {
            Handler(Looper.getMainLooper()).post {
                onResult.invoke(started)
            }
        }
    }
}

fun Context.startScoopService(): Boolean {
    if (capturecrash.log.dev.ScoopApplication.xposedActive()) {
        return true
    }
    if (!isPermissionGranted()) {
        return false
    }

    return try {
        ContextCompat.startForegroundService(this, serviceIntent)
        true
    } catch (e: Exception) {
        // Handles ForegroundServiceStartNotAllowedException on Android 12 - 17
        Log.e("CapyCrash", "Failed to start foreground service", e)
        false
    }
}

fun Context.stopScoopService() {
    try {
        if (stopService(serviceIntent)) {
            isServiceActive = false
        }
    } catch (e: Exception) {
        Log.e("CapyCrash", "Failed to stop service", e)
    }
}

private val Context.serviceIntent
    get() = Intent(this, CrashDetectorService::class.java)
