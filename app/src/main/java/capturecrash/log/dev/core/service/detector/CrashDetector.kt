package capturecrash.log.dev.core.service.detector

import android.content.Intent
import android.util.Log
import capturecrash.log.dev.BuildConfig
import capturecrash.log.dev.core.receiver.CrashReceiver
import capturecrash.log.dev.util.Intents
import capturecrash.log.dev.util.openPrivilegedLogcatStream
import java.io.BufferedReader
import java.io.InputStreamReader

abstract class CrashDetector : ICrashDetector.Stub() {

    private var cleanupCallback: (() -> Unit)? = null
    @Volatile
    private var isStopped = false

    @Volatile
    private var lastReportedTime = 0L
    @Volatile
    private var lastReportedKey = ""

    init {
        val readThread = object : Thread("CapyCrash-LogcatReader") {
            var pendingLine: String? = null
            override fun run() {
                Log.d("CrashDetector", "thread started")
                try {
                    val (inputStream, cleanup) = openPrivilegedLogcatStream()
                    cleanupCallback = cleanup
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    while (!isStopped) {
                        val line = pendingLine ?: reader.readLine() ?: break
                        pendingLine = null
                        when {
                            line.matches(fePattern) -> {
                                pendingLine = reportJavaCrash(reader, line.replace(arPattern, ""))
                            }
                            line.matches(nativeBannerPattern) -> {
                                pendingLine = reportNativeCrash(reader, line.replace(debugPrefixPattern, ""))
                            }
                            line.matches(anrPattern) -> {
                                pendingLine = reportAnrCrash(reader, line.replace(amErrorPrefixPattern, ""))
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (!isStopped) {
                        Log.e("CrashDetector", "Logcat reader interrupted", e)
                    }
                }
            }
        }
        readThread.start()
    }

    /**
     * Parses Java/Kotlin uncaught exceptions (E AndroidRuntime: FATAL EXCEPTION: ...)
     */
    private fun reportJavaCrash(reader: BufferedReader, firstLine: String): String? {
        val lines = ArrayList<String>()
        var index = 0
        var lastLine: String? = null
        var foundTrace = false
        var foundTraceAt = 0
        while (!isStopped) {
            val line = reader.readLine()
            lastLine = line
            if (line != null && line.matches(beginPattern)) {
                if (!foundTrace && line.matches(linePattern)) {
                    foundTrace = true
                    foundTraceAt = index
                }
                lines.add(line.replace(arPattern, ""))
            } else {
                break
            }
            index++
        }
        if (lines.size < 2) return lastLine
        val packageName = processInfoPattern.find(lines[0])?.groupValues?.get(1)?.let(::cleanProcessToPackage)
            ?: return lastLine
        val descEnd = if (foundTraceAt > 1) foundTraceAt else lines.size
        val description = lines.subList(1, descEnd).joinToString("\n")
        val stackTrace = "$firstLine\n" + lines.joinToString("\n")

        emitCrash(packageName, description, stackTrace)
        return lastLine
    }

    /**
     * Parses Native C/C++ crashes (F DEBUG : *** *** *** *** ...)
     */
    private fun reportNativeCrash(reader: BufferedReader, firstLine: String): String? {
        val lines = ArrayList<String>()
        lines.add(firstLine)
        var lastLine: String? = null
        var pkgName: String? = null
        var signalLine: String? = null
        var abortLine: String? = null

        while (!isStopped) {
            val line = reader.readLine()
            lastLine = line
            if (line != null && line.matches(debugLinePattern)) {
                val content = line.replace(debugPrefixPattern, "")
                lines.add(content)

                if (pkgName == null) {
                    nativeCmdlinePattern.find(content)?.groupValues?.get(1)?.let { rawProc ->
                        pkgName = cleanProcessToPackage(rawProc)
                    }
                }
                if (signalLine == null && content.trimStart().startsWith("signal ")) {
                    signalLine = content.trim()
                }
                if (abortLine == null && content.trimStart().startsWith("Abort message:")) {
                    abortLine = content.trim()
                }
            } else {
                break
            }
        }

        val resolvedPkg = pkgName ?: return lastLine
        if (resolvedPkg == "android" || resolvedPkg.startsWith("/")) return lastLine

        val descBuilder = StringBuilder("[Native Crash] ")
        if (signalLine != null) {
            descBuilder.append(signalLine)
            if (abortLine != null) {
                descBuilder.append(" | ").append(abortLine)
            }
        } else if (abortLine != null) {
            descBuilder.append(abortLine)
        } else {
            descBuilder.append("Fatal native signal in ").append(resolvedPkg)
        }

        emitCrash(resolvedPkg, descBuilder.toString(), lines.joinToString("\n"))
        return lastLine
    }

    /**
     * Parses Application Not Responding logs (E ActivityManager: ANR in <pkg> ...)
     */
    private fun reportAnrCrash(reader: BufferedReader, firstLine: String): String? {
        val lines = ArrayList<String>()
        lines.add(firstLine)
        var lastLine: String? = null
        val rawProcess = anrExtractPattern.find(firstLine)?.groupValues?.get(1)
        val pkgName = rawProcess?.let(::cleanProcessToPackage)
        var reasonLine: String? = null

        while (!isStopped) {
            val line = reader.readLine()
            lastLine = line
            if (line != null && line.matches(amErrorLinePattern)) {
                val content = line.replace(amErrorPrefixPattern, "")
                lines.add(content)
                if (reasonLine == null && content.trimStart().startsWith("Reason:")) {
                    reasonLine = content.trim()
                }
            } else {
                break
            }
        }

        val resolvedPkg = pkgName ?: return lastLine
        val description = if (reasonLine != null) {
            "[ANR] $reasonLine"
        } else {
            "[ANR] $firstLine"
        }

        emitCrash(resolvedPkg, description, lines.joinToString("\n"))
        return lastLine
    }

    private fun cleanProcessToPackage(rawProcess: String): String {
        val trimmed = rawProcess.trim()
        val colonIdx = trimmed.indexOf(':')
        return if (colonIdx > 0) trimmed.substring(0, colonIdx) else trimmed
    }

    private fun emitCrash(packageName: String, description: String, stackTrace: String) {
        if (packageName.isEmpty() || packageName == BuildConfig.APPLICATION_ID) return
        val now = System.currentTimeMillis()
        val key = "$packageName|$description"
        if (now - lastReportedTime < 1500L && key == lastReportedKey) {
            return
        }
        lastReportedTime = now
        lastReportedKey = key

        val safeDesc = if (description.length > 2048) {
            description.substring(0, 2048) + "\n\n... [Truncated]"
        } else {
            description
        }
        val safeTrace = if (stackTrace.length > 131072) {
            stackTrace.substring(0, 131072) + "\n\n... [Truncated]"
        } else {
            stackTrace
        }

        val intent = Intent(Intents.INTENT_ACTION)
            .setClassName(BuildConfig.APPLICATION_ID, CrashReceiver::class.java.name)
            .putExtra(Intents.INTENT_PACKAGE_NAME, packageName)
            .putExtra(Intents.INTENT_TIME, now)
            .putExtra(Intents.INTENT_DESCRIPTION, safeDesc)
            .putExtra(Intents.INTENT_STACKTRACE, safeTrace)
        sendBroadcast(intent)
    }

    protected abstract fun sendBroadcast(intent: Intent)

    override fun kill() {
        isStopped = true
        try {
            cleanupCallback?.invoke()
        } catch (_: Throwable) {
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        // Java AndroidRuntime patterns (supports normal process names & multi-process names like pkg:remote)
        private val beginPattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+E AndroidRuntime: (.)*")
        private val arPattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+E AndroidRuntime: ")
        private val fePattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+E AndroidRuntime: FATAL EXCEPTION: (?:.)*")
        private val linePattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+E AndroidRuntime: \tat(.)*")
        private val processInfoPattern =
            Regex("Process: ([a-zA-Z0-9_\\.\\:\\-]+), PID: ([0-9]+)")

        // Native Crash (DEBUG / tombstoned) patterns
        private val nativeBannerPattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+[FE] DEBUG( )*: \\*\\*\\* \\*\\*\\* \\*\\*\\* (?:.)*")
        private val debugLinePattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+[FE] DEBUG( )*: (?:.)*")
        private val debugPrefixPattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+[FE] DEBUG( )*: ")
        private val nativeCmdlinePattern =
            Regex(">>>\\s*([^\\s<]+)\\s*<<<")

        // ANR (ActivityManager) patterns
        private val anrPattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+E ActivityManager: ANR in (?:.)*")
        private val amErrorLinePattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+E ActivityManager: (?:.)*")
        private val amErrorPrefixPattern =
            Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}( )+[0-9]+( )+[0-9]+( )+E ActivityManager: ")
        private val anrExtractPattern =
            Regex("ANR in ([a-zA-Z0-9_\\.\\:\\-]+)")
    }
}
