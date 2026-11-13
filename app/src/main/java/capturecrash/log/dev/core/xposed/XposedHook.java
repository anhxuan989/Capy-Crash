package capturecrash.log.dev.core.xposed;

import android.app.Application;
import android.app.ApplicationErrorReport;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import capturecrash.log.dev.BuildConfig;
import capturecrash.log.dev.ScoopApplication;
import capturecrash.log.dev.core.receiver.CrashReceiver;
import capturecrash.log.dev.util.Intents;

@SuppressWarnings("WeakerAccess")
public class XposedHook implements IXposedHookLoadPackage {

    /**
     * Maximum stackTrace length sent inside Broadcast Intent extras to prevent
     * Binder TransactionTooLargeException / system_server Binder buffer exhaustion.
     */
    private static final int MAX_BROADCAST_TRACE_CHARS = 128 * 1024;

    /**
     * Minimum uptime before system_server starts dispatching crash broadcasts,
     * plus a check for boot completion / unlocked user storage so system_server
     * never blocks or fails during early boot.
     */
    private static final long MIN_SYSTEM_UPTIME_MS = 30_000L;

    /**
     * Rate-limit window & cap inside system_server to prevent broadcast storms
     * if a background service or app enters a tight crash loop.
     */
    private static final long RATE_WINDOW_MS = 10_000L;
    private static final int MAX_BROADCASTS_PER_WINDOW = 8;

    private static volatile String mPkg;
    private static volatile Application mApplication;
    private static final AtomicBoolean mSent = new AtomicBoolean(false);
    private static final Set<Class<?>> sHookedHandlerClasses =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>()));

    private static long sLastCrashTime = 0L;
    private static String sLastCrashKey = "";
    private static long sRateWindowStart = 0L;
    private static int sRateWindowCount = 0;

    private static volatile Handler sSystemDispatchHandler;

    private static Handler getSystemDispatchHandler() {
        Handler h = sSystemDispatchHandler;
        if (h == null) {
            synchronized (XposedHook.class) {
                h = sSystemDispatchHandler;
                if (h == null) {
                    HandlerThread thread = new HandlerThread("CapyCrash-Dispatcher", android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    thread.start();
                    h = new Handler(thread.getLooper());
                    sSystemDispatchHandler = h;
                }
            }
        }
        return h;
    }

    private final XC_MethodHook uncaughtExceptionHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!mSent.compareAndSet(false, true)) {
                    return;
                }
                Throwable t = null;
                if (param.args != null) {
                    for (Object arg : param.args) {
                        if (arg instanceof Throwable) {
                            t = (Throwable) arg;
                            break;
                        }
                    }
                }
                if (t == null) {
                    mSent.set(false);
                    return;
                }

                Context ctx = getBestAppContext();
                if (ctx == null) {
                    mSent.set(false);
                    return;
                }
                String pkg = ctx.getPackageName();
                if (pkg == null || pkg.isEmpty()) {
                    pkg = mPkg;
                }
                if (pkg == null || pkg.isEmpty() || "android".equals(pkg) || BuildConfig.APPLICATION_ID.equals(pkg)) {
                    return;
                }

                sendCrashBroadcast(
                        ctx,
                        pkg,
                        t.toString(),
                        Log.getStackTraceString(t)
                );
            } catch (Throwable ignored) {
            }
        }
    };

    private final XC_MethodHook setUncaughtExceptionHandlerHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                    hookUncaughtException(param.args[0].getClass());
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static Context getBestAppContext() {
        Application cached = mApplication;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Application currentApp = (Application) XposedHelpers.callStaticMethod(atClass, "currentApplication");
            if (currentApp != null) {
                mApplication = currentApp;
                return currentApp;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }

        // System Server: ONLY hook ActivityManagerService inside the real "android" process.
        // Never install Thread/Application uncaught hooks in system_server or any shared android process.
        if ("android".equals(lpparam.packageName)) {
            if ("android".equals(lpparam.processName)) {
                hookSystemServerCrashHandlers(lpparam);
            }
            return;
        }

        // Own app: ONLY mark xposedActive() = true and DO NOT hook crash handlers in our own process
        // to avoid recursive crash reporting loops.
        if (BuildConfig.APPLICATION_ID.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                        ScoopApplication.class.getName(),
                        lpparam.classLoader,
                        "xposedActive",
                        XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {
                XposedBridge.log("CapyCrash: Failed to hook xposedActive: " + t);
            }
            return;
        }

        // Critical OS components (SystemUI, Phone/Telephony, NFC, Bluetooth, Providers):
        // Let ActivityManagerService in system_server capture their crashes asynchronously
        // instead of hooking Thread/RuntimeInit inside their live processes.
        if ("com.android.systemui".equals(lpparam.packageName)
                || "com.android.phone".equals(lpparam.packageName)
                || "com.android.nfc".equals(lpparam.packageName)
                || "com.android.bluetooth".equals(lpparam.packageName)
                || "android.uid.system".equals(lpparam.processName)
                || "system".equals(lpparam.processName)) {
            return;
        }

        mPkg = lpparam.packageName;
        try {
            XposedHelpers.findAndHookConstructor(Application.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    mApplication = (Application) param.thisObject;
                    mSent.set(false);
                }
            });
            XposedHelpers.findAndHookMethod(
                    Thread.class,
                    "setDefaultUncaughtExceptionHandler",
                    Thread.UncaughtExceptionHandler.class,
                    setUncaughtExceptionHandlerHook);

            XposedHelpers.findAndHookMethod(
                    Thread.class,
                    "setUncaughtExceptionHandler",
                    Thread.UncaughtExceptionHandler.class,
                    setUncaughtExceptionHandlerHook);

            XposedHelpers.findAndHookMethod(
                    ThreadGroup.class,
                    "uncaughtException",
                    Thread.class,
                    Throwable.class,
                    uncaughtExceptionHook);

            Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            if (defaultHandler != null) {
                hookUncaughtException(defaultHandler.getClass());
            }
        } catch (Throwable t) {
            XposedBridge.log("CapyCrash: Failed to install app hooks in " + lpparam.packageName + ": " + t);
        }
    }

    /**
     * Hooks ActivityManagerService.addErrorToDropBox and handleApplicationCrashInner
     * inside "android" (System Framework).
     *
     * IMPORTANT FOR OS STABILITY:
     * - Uses afterHookedMethod so AMS finishes its own internal crash cleanup first.
     * - Never performs file I/O, heavy string parsing, or Binder broadcasts on the calling
     *   AMS thread (which often holds ActivityManagerService's global synchronized lock,
     *   triggering Watchdog 60s kills & OS reboot loops if blocked!).
     * - Clears Binder calling identity before interacting with system services.
     * - Skips broadcasts until boot has completed and Credential Encrypted storage is unlocked.
     */
    private void hookSystemServerCrashHandlers(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> amsClass = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService",
                    lpparam.classLoader
            );

            // 1. Hook addErrorToDropBox (Captures Java Crash, Native Crash, ANR, WTF)
            XposedBridge.hookAllMethods(amsClass, "addErrorToDropBox", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        scheduleDropBoxError(param);
                    } catch (Throwable t) {
                        XposedBridge.log("CapyCrash: Error in addErrorToDropBox hook: " + t);
                    }
                }
            });

            // 2. Fallback Hook handleApplicationCrashInner
            XposedBridge.hookAllMethods(amsClass, "handleApplicationCrashInner", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        scheduleAmsCrashInner(param);
                    } catch (Throwable t) {
                        XposedBridge.log("CapyCrash: Error in handleApplicationCrashInner hook: " + t);
                    }
                }
            });

            XposedBridge.log("CapyCrash: Hooked ActivityManagerService safely (async afterHookedMethod)");
        } catch (Throwable t) {
            XposedBridge.log("CapyCrash: Could not hook ActivityManagerService: " + t);
        }
    }

    private static volatile boolean sUserStorageReady = false;

    private static boolean isSystemReadyForBroadcast(Context systemContext) {
        if (sUserStorageReady) {
            return true;
        }
        if (SystemClock.uptimeMillis() < MIN_SYSTEM_UPTIME_MS) {
            return false;
        }
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            String bootCompleted = (String) XposedHelpers.callStaticMethod(sysProps, "get", "sys.boot_completed", "0");
            if (!"1".equals(bootCompleted)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final long token = Binder.clearCallingIdentity();
            try {
                UserManager um = (UserManager) systemContext.getSystemService(Context.USER_SERVICE);
                if (um != null && !um.isUserUnlocked()) {
                    return false;
                }
            } catch (Throwable ignored) {
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        sUserStorageReady = true;
        return true;
    }

    private void scheduleDropBoxError(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 3) return;

        final String eventType = param.args[0] instanceof String ? (String) param.args[0] : null;
        if (eventType == null) return;

        // Ignore non-crash noisy dropbox events (e.g., strictmode, lowmem, battery, etc.)
        // especially system_server's own strictmode/wtf events to prevent feedback loops.
        String lowerEvent = eventType.toLowerCase();
        if (lowerEvent.startsWith("system_server_") || lowerEvent.contains("strictmode") || lowerEvent.contains("lowmem")) {
            return;
        }
        if (!lowerEvent.contains("crash") && !lowerEvent.contains("anr") && !lowerEvent.contains("wtf")) {
            return;
        }

        Object processRecord = param.args.length > 1 ? param.args[1] : null;
        final String processName = param.args.length > 2 && param.args[2] instanceof String ? (String) param.args[2] : null;
        final String pkgName = resolvePackageName(processRecord, processName);
        if (isIgnoredSystemOrSelfPackage(pkgName, processName)) {
            return;
        }

        final Context systemContext = getAmsContext(param.thisObject);
        if (systemContext == null || !isSystemReadyForBroadcast(systemContext)) {
            return;
        }

        final String subject = param.args.length > 6 && param.args[6] instanceof String ? (String) param.args[6] : null;
        final String report = param.args.length > 7 && param.args[7] instanceof String ? (String) param.args[7] : null;

        File foundFile = null;
        ApplicationErrorReport.CrashInfo foundCrashInfo = null;
        for (Object arg : param.args) {
            if (arg instanceof File) {
                foundFile = (File) arg;
            } else if (arg instanceof ApplicationErrorReport.CrashInfo) {
                foundCrashInfo = (ApplicationErrorReport.CrashInfo) arg;
            }
        }
        final File dataFile = foundFile;
        final String excClass = foundCrashInfo != null ? foundCrashInfo.exceptionClassName : null;
        final String excMsg = foundCrashInfo != null ? foundCrashInfo.exceptionMessage : null;
        final String crashStack = foundCrashInfo != null ? foundCrashInfo.stackTrace : null;

        // Offload all string assembly, file reading, and broadcast dispatch to a background thread
        // so we NEVER block ActivityManagerService locks or trigger SystemServer Watchdog!
        getSystemDispatchHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    processDropBoxErrorAsync(
                            systemContext,
                            eventType,
                            pkgName,
                            processName,
                            subject,
                            report,
                            dataFile,
                            excClass,
                            excMsg,
                            crashStack
                    );
                } catch (Throwable t) {
                    XposedBridge.log("CapyCrash: Async DropBox processing failed: " + t);
                }
            }
        });
    }

    private static void processDropBoxErrorAsync(
            Context systemContext,
            String eventType,
            String pkgName,
            String processName,
            String subject,
            String report,
            File dataFile,
            String excClassName,
            String excMsg,
            String crashStack
    ) {
        String typePrefix = formatEventTypePrefix(eventType);
        String description;
        String stackTrace;

        if (crashStack != null && !crashStack.isEmpty()) {
            String excClass = excClassName != null ? excClassName : "Exception";
            String baseDesc = (excMsg != null && !excMsg.isEmpty()) ? (excClass + ": " + excMsg) : excClass;
            description = typePrefix.isEmpty() ? baseDesc : (typePrefix + " " + baseDesc);
            stackTrace = crashStack;
            if (report != null && !report.isEmpty() && stackTrace.length() < MAX_BROADCAST_TRACE_CHARS && !stackTrace.contains(report)) {
                stackTrace = stackTrace + "\n\n--- System Report ---\n" + report;
            }
        } else {
            // Delay slightly for ANRs so AMS/Tombstoned finishes flushing the trace file
            // before our background thread reads it (avoids reading an in-progress trace file).
            if (dataFile != null && eventType != null && eventType.toLowerCase().contains("anr")) {
                try {
                    Thread.sleep(600L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            StringBuilder traceBuilder = new StringBuilder();
            if (subject != null && !subject.isEmpty()) {
                traceBuilder.append("Subject: ").append(subject).append("\n");
            }
            if (processName != null && !processName.isEmpty()) {
                traceBuilder.append("Process: ").append(processName).append("\n\n");
            }
            if (report != null && !report.isEmpty()) {
                traceBuilder.append(truncateText(report, MAX_BROADCAST_TRACE_CHARS / 2)).append("\n");
            }
            // Only read regular files (never pipes/FIFOs or /proc files that could block)
            if (traceBuilder.length() < MAX_BROADCAST_TRACE_CHARS
                    && dataFile != null
                    && dataFile.isFile()
                    && dataFile.canRead()) {
                int remaining = Math.min(64 * 1024, MAX_BROADCAST_TRACE_CHARS - traceBuilder.length());
                if (remaining > 1024) {
                    String fileContent = readFilePreview(dataFile, remaining);
                    if (!fileContent.isEmpty()) {
                        traceBuilder.append("\n--- File: ").append(dataFile.getName()).append(" ---\n");
                        traceBuilder.append(fileContent);
                    }
                }
            }
            stackTrace = traceBuilder.toString().trim();
            if (stackTrace.isEmpty()) {
                return;
            }
            description = extractSummaryDescription(typePrefix, subject, stackTrace);
        }

        sendCrashBroadcastFromSystem(systemContext, pkgName, description, stackTrace);
    }

    private void scheduleAmsCrashInner(XC_MethodHook.MethodHookParam param) {
        if (param.args == null) return;

        String eventType = null;
        Object processRecord = null;
        String processName = null;
        ApplicationErrorReport.CrashInfo crashInfo = null;

        for (Object arg : param.args) {
            if (arg instanceof String) {
                if (eventType == null) {
                    eventType = (String) arg;
                } else if (processName == null) {
                    processName = (String) arg;
                }
            } else if (arg instanceof ApplicationErrorReport.CrashInfo) {
                crashInfo = (ApplicationErrorReport.CrashInfo) arg;
            } else if (arg != null && arg.getClass().getName().endsWith("ProcessRecord")) {
                processRecord = arg;
            }
        }

        if (crashInfo == null || crashInfo.stackTrace == null || crashInfo.stackTrace.isEmpty()) {
            return;
        }

        final String pkgName = resolvePackageName(processRecord, processName);
        if (isIgnoredSystemOrSelfPackage(pkgName, processName)) {
            return;
        }

        final Context systemContext = getAmsContext(param.thisObject);
        if (systemContext == null || !isSystemReadyForBroadcast(systemContext)) {
            return;
        }

        final String finalEventType = eventType != null ? eventType : "crash";
        final String excClass = crashInfo.exceptionClassName != null ? crashInfo.exceptionClassName : "Exception";
        final String excMsg = crashInfo.exceptionMessage;
        final String stackTrace = crashInfo.stackTrace;

        getSystemDispatchHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    String typePrefix = formatEventTypePrefix(finalEventType);
                    String baseDesc = (excMsg != null && !excMsg.isEmpty())
                            ? (excClass + ": " + excMsg)
                            : excClass;
                    String description = typePrefix.isEmpty() ? baseDesc : (typePrefix + " " + baseDesc);
                    sendCrashBroadcastFromSystem(systemContext, pkgName, description, stackTrace);
                } catch (Throwable t) {
                    XposedBridge.log("CapyCrash: Async AMS crash processing failed: " + t);
                }
            }
        });
    }

    private static Context getAmsContext(Object amsInstance) {
        if (amsInstance == null) return null;
        try {
            return (Context) XposedHelpers.getObjectField(amsInstance, "mContext");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isIgnoredSystemOrSelfPackage(String pkgName, String processName) {
        if (pkgName == null || pkgName.isEmpty()) {
            return true;
        }
        if ("android".equals(pkgName)
                || "system".equals(pkgName)
                || "system_server".equals(pkgName)
                || pkgName.startsWith("/")
                || BuildConfig.APPLICATION_ID.equals(pkgName)) {
            return true;
        }
        if (processName != null && (processName.equals("system")
                || processName.equals("system_server")
                || processName.startsWith(BuildConfig.APPLICATION_ID))) {
            return true;
        }
        return false;
    }

    private static String resolvePackageName(Object processRecord, String processName) {
        String pkgName = null;
        if (processRecord != null) {
            try {
                ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.getObjectField(processRecord, "info");
                if (appInfo != null) {
                    pkgName = appInfo.packageName;
                }
            } catch (Throwable ignored) {
            }
        }
        if (pkgName == null || pkgName.isEmpty()) {
            if (processName != null && !processName.isEmpty()) {
                int colonIdx = processName.indexOf(':');
                pkgName = colonIdx > 0 ? processName.substring(0, colonIdx) : processName;
            }
        }
        return pkgName;
    }

    private static String formatEventTypePrefix(String eventType) {
        if (eventType == null) return "";
        String lower = eventType.toLowerCase();
        if (lower.contains("native_crash")) {
            return "[Native Crash]";
        } else if (lower.contains("anr")) {
            return "[ANR]";
        } else if (lower.contains("wtf")) {
            return "[WTF]";
        }
        return "";
    }

    private static String extractSummaryDescription(String typePrefix, String subject, String stackTrace) {
        String prefix = typePrefix.isEmpty() ? "[Crash]" : typePrefix;
        String[] lines = stackTrace.split("\n");
        String signalLine = null;
        String abortLine = null;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (signalLine == null && (line.startsWith("signal ") || line.contains("SIGSEGV") || line.contains("SIGABRT") || line.contains("SIGILL") || line.contains("SIGBUS") || line.contains("SIGFPE"))) {
                signalLine = line;
            } else if (abortLine == null && line.startsWith("Abort message:")) {
                abortLine = line;
            }
        }
        if (signalLine != null) {
            return prefix + " " + signalLine + (abortLine != null ? " | " + abortLine : "");
        }
        if (subject != null && !subject.isEmpty()) {
            return prefix + " " + subject;
        }
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (!line.isEmpty() && !line.startsWith("Process:") && !line.startsWith("Build:") && !line.startsWith("Package:") && !line.startsWith("***")) {
                return prefix + " " + line;
            }
        }
        return prefix + " Application error";
    }

    private static String readFilePreview(File file, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int total = 0;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                total += line.length() + 1;
                if (total >= maxBytes) {
                    sb.append("\n... [Truncated] ...\n");
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String truncateText(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n\n... [Truncated] ...";
    }

    private static synchronized boolean shouldAllowBroadcast(String pkgName, String description, boolean enforceRateLimit) {
        long now = SystemClock.elapsedRealtime();
        String safeDesc = description != null && description.length() > 200
                ? description.substring(0, 200)
                : description;
        String key = pkgName + "|" + safeDesc;
        if (now - sLastCrashTime < 2000L && key.equals(sLastCrashKey)) {
            return false;
        }
        if (enforceRateLimit) {
            if (now - sRateWindowStart > RATE_WINDOW_MS) {
                sRateWindowStart = now;
                sRateWindowCount = 0;
            }
            if (sRateWindowCount >= MAX_BROADCASTS_PER_WINDOW) {
                return false;
            }
            sRateWindowCount++;
        }
        sLastCrashTime = now;
        sLastCrashKey = key;
        return true;
    }

    private static void sendCrashBroadcast(Context context, String pkgName, String description, String stackTrace) {
        try {
            if (!shouldAllowBroadcast(pkgName, description, false)) return;
            String safeDesc = truncateText(description, 2048);
            String safeTrace = truncateText(stackTrace, MAX_BROADCAST_TRACE_CHARS);

            Intent intent = new Intent(Intents.INTENT_ACTION)
                    .setClassName(BuildConfig.APPLICATION_ID, CrashReceiver.class.getName())
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(Intents.INTENT_PACKAGE_NAME, pkgName)
                    .putExtra(Intents.INTENT_TIME, System.currentTimeMillis())
                    .putExtra(Intents.INTENT_DESCRIPTION, safeDesc)
                    .putExtra(Intents.INTENT_STACKTRACE, safeTrace);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private static void sendCrashBroadcastFromSystem(Context systemContext, String pkgName, String description, String stackTrace) {
        if (!shouldAllowBroadcast(pkgName, description, true)) return;
        if (!isSystemReadyForBroadcast(systemContext)) return;

        String safeDesc = truncateText(description, 2048);
        String safeTrace = truncateText(stackTrace, MAX_BROADCAST_TRACE_CHARS);

        Intent intent = new Intent(Intents.INTENT_ACTION)
                .setClassName(BuildConfig.APPLICATION_ID, CrashReceiver.class.getName())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(Intents.INTENT_PACKAGE_NAME, pkgName)
                .putExtra(Intents.INTENT_TIME, System.currentTimeMillis())
                .putExtra(Intents.INTENT_DESCRIPTION, safeDesc)
                .putExtra(Intents.INTENT_STACKTRACE, safeTrace);

        // CRITICAL: Clear Binder calling identity in system_server so sendBroadcastAsUser runs
        // under system_server's own UID (1000) instead of a dying app's unprivileged UID!
        final long token = Binder.clearCallingIdentity();
        try {
            UserHandle userHandle = null;
            try {
                userHandle = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "CURRENT");
            } catch (Throwable ignored) {
            }
            if (userHandle == null) {
                userHandle = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
            }
            XposedHelpers.callMethod(systemContext, "sendBroadcastAsUser", intent, userHandle);
        } catch (Throwable t) {
            try {
                systemContext.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void hookUncaughtException(Class<?> clazz) {
        if (clazz == null) return;
        int i = 0;
        do {
            if (clazz == Object.class) break;
            if (sHookedHandlerClasses.contains(clazz)) {
                return;
            }
            try {
                XposedHelpers.findAndHookMethod(
                        clazz,
                        "uncaughtException",
                        Thread.class,
                        Throwable.class,
                        uncaughtExceptionHook);
                sHookedHandlerClasses.add(clazz);
                Log.d("CapyCrash", "hookUncaughtException (" + mPkg
                        + "): Hooked class " + clazz.getName() + " after " + i + " loops");
                return;
            } catch (Throwable ignore) {
            }
            i++;
        } while ((clazz = clazz.getSuperclass()) != null);
    }
}
