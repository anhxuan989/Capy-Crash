package capturecrash.log.dev.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import capturecrash.log.dev.BuildConfig
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import java.io.InputStream

const val readLogsPermission = Manifest.permission.READ_LOGS

fun Context.readLogsPermissionGranted(): Boolean {
    return ContextCompat.checkSelfPermission(
        this, readLogsPermission
    ) == PackageManager.PERMISSION_GRANTED
}

fun isShizukuAvailable(): Boolean {
    return try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }
}

fun isShizukuPermissionGranted(): Boolean {
    return try {
        if (!isShizukuAvailable()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }
}

fun isRootAvailable(): Boolean {
    return try {
        Shell.getShell().isRoot
    } catch (e: Throwable) {
        false
    }
}

fun Context.isPermissionGranted(tryGranting: Boolean = true): Boolean {
    if (isShizukuPermissionGranted()) {
        if (!readLogsPermissionGranted() && tryGranting) {
            runShizukuGrantPermission()
        }
        return true
    }
    if (tryGranting && isRootAvailable()) {
        runReadLogsGrantShell()
        return true
    }
    val granted = readLogsPermissionGranted()
    return if (!granted && tryGranting) {
        runReadLogsGrantShell()
        readLogsPermissionGranted()
    } else {
        granted
    }
}

fun runReadLogsGrantShell() {
    try {
        val pkg = BuildConfig.APPLICATION_ID
        val commands = mutableListOf(
            "pm grant $pkg $readLogsPermission",
            "appops set $pkg SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION allow"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            commands.add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            commands.add("appops set $pkg READ_DEVICE_IDENTIFIERS allow")
        }
        Shell.cmd(*commands.toTypedArray()).exec()
    } catch (e: Throwable) {
        Log.w("CapyCrash", "Root grant failed", e)
    }
}

fun runShizukuGrantPermission() {
    try {
        val pkg = BuildConfig.APPLICATION_ID
        execShizukuCommand(arrayOf("pm", "grant", pkg, readLogsPermission))?.waitFor()
        execShizukuCommand(
            arrayOf("appops", "set", pkg, "SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION", "allow")
        )?.waitFor()
    } catch (e: Throwable) {
        Log.w("CapyCrash", "Shizuku grant failed", e)
    }
}

fun execShizukuCommand(cmd: Array<String>): Process? {
    return try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(null, cmd, null, null) as? Process
    } catch (e: Throwable) {
        Log.e("CapyCrash", "Failed to start Shizuku process", e)
        null
    }
}

/**
 * Starts a logcat stream using the highest available privilege on Android 13 - 17 (API 33 - 37)
 * so that background logcat reading is not blocked by LogcatManagerService prompts.
 */
fun openPrivilegedLogcatStream(): Pair<InputStream, () -> Unit> {
    // 1. Try Root via libsu / su process
    if (isRootAvailable()) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -c")).waitFor()
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -b main,system,crash -v threadtime"))
            return Pair(proc.inputStream) { proc.destroy() }
        } catch (e: Throwable) {
            Log.w("CapyCrash", "Root logcat stream failed, falling back", e)
        }
    }

    // 2. Try Shizuku (runs as ADB shell UID 2000, bypassing Android 13-17 per-app logd prompt)
    if (isShizukuPermissionGranted()) {
        try {
            execShizukuCommand(arrayOf("logcat", "-c"))?.waitFor()
            val proc = execShizukuCommand(arrayOf("logcat", "-b", "main,system,crash", "-v", "threadtime"))
            if (proc != null) {
                return Pair(proc.inputStream) { proc.destroy() }
            }
        } catch (e: Throwable) {
            Log.w("CapyCrash", "Shizuku logcat stream failed, falling back", e)
        }
    }

    // 3. Standard process (READ_LOGS via ADB)
    try {
        Runtime.getRuntime().exec("logcat -c").waitFor()
    } catch (_: Throwable) {
    }
    val proc = Runtime.getRuntime().exec("logcat -b main,system,crash -v threadtime")
    return Pair(proc.inputStream) { proc.destroy() }
}
