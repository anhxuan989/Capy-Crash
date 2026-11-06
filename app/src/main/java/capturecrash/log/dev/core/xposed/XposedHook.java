package capturecrash.log.dev.core.xposed;

import android.app.Application;
import android.app.ApplicationErrorReport;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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

    private static String mPkg;
    private static Application mApplication;
    private static boolean mSent;
    private static long sLastCrashTime = 0L;
    private static String sLastCrashKey = "";

    private final XC_MethodHook uncaughtExceptionHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (mSent) {
                return;
            }
            Throwable t = null;
            for (Object arg : param.args) {
                if (arg instanceof Throwable) {
                    t = (Throwable) arg;
                    break;
                }
            }
            if (t == null) return;

            Context ctx = getBestAppContext();
            if (ctx == null) return;
            String pkg = mPkg != null ? mPkg : ctx.getPackageName();

            sendCrashBroadcast(
                    ctx,
                    pkg,
                    t.toString(),
                    Log.getStackTraceString(t)
            );
            mSent = true;
        }
    };

    private final XC_MethodHook setUncaughtExceptionHandlerHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (param.args.length > 0 && param.args[0] != null) {
                hookUncaughtException(param.args[0].getClass());
            }
        }
    };

    private static Context getBestAppContext() {
        if (mApplication != null) {
            return mApplication;
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
        if ("android".equals(lpparam.packageName)) {
            hookSystemServerCrashHandlers(lpparam);
            return;
        }

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
        }

        mPkg = lpparam.packageName;
        XposedHelpers.findAndHookConstructor(Application.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                mApplication = (Application) param.thisObject;
                mSent = false;
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

        try {
            XposedHelpers.findAndHookMethod(
                    Thread.class,
                    "dispatchUncaughtException",
                    Throwable.class,
                    uncaughtExceptionHook);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> runtimeInitClass = XposedHelpers.findClass("com.android.internal.os.RuntimeInit", lpparam.classLoader);
            XposedBridge.hookAllMethods(runtimeInitClass, "wtf", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    for (Object arg : param.args) {
                        if (arg instanceof Throwable) {
                            Throwable t = (Throwable) arg;
                            Context ctx = getBestAppContext();
                            if (ctx != null) {
                                sendCrashBroadcast(ctx, ctx.getPackageName(), "[WTF] " + t, Log.getStackTraceString(t));
                            }
                            break;
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        if (defaultHandler != null) {
            hookUncaughtException(defaultHandler.getClass());
        }
    }

    /**
     * Hooks both ActivityManagerService.addErrorToDropBox and handleApplicationCrashInner
     * inside "android" (System Framework).
     *
     * This captures ALL crash types across Android 8 -> 17:
     * - Java/Kotlin crashes (crash / data_app_crash / system_app_crash)
     * - Native C/C++ crashes (native_crash / data_app_native_crash / SIGSEGV / SIGABRT)
     * - ANR (anr / data_app_anr)
     * - System WTF errors (wtf / data_app_wtf)
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
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        handleDropBoxError(param);
                    } catch (Throwable t) {
                        XposedBridge.log("CapyCrash: Error in addErrorToDropBox hook: " + t);
                    }
                }
            });

            // 2. Fallback Hook handleApplicationCrashInner
            XposedBridge.hookAllMethods(amsClass, "handleApplicationCrashInner", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        handleAmsCrashInner(param);
                    } catch (Throwable t) {
                        XposedBridge.log("CapyCrash: Error in handleApplicationCrashInner hook: " + t);
                    }
                }
            });

            XposedBridge.log("CapyCrash: Hooked ActivityManagerService (addErrorToDropBox + handleApplicationCrashInner)");
        } catch (Throwable t) {
            XposedBridge.log("CapyCrash: Could not hook ActivityManagerService: " + t);
        }
    }

    private void handleDropBoxError(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 3) return;

        String eventType = param.args[0] instanceof String ? (String) param.args[0] : null;
        if (eventType == null) return;

        // Typical signatures of addErrorToDropBox:
        // (0: eventType, 1: processRecord, 2: processName, 3: activity, 4: parent, 5: parentProc,
        //  6: subject, 7: report, 8: dataFile, 9: crashInfo, ...)
        Object processRecord = param.args.length > 1 ? param.args[1] : null;
        String processName = param.args.length > 2 && param.args[2] instanceof String ? (String) param.args[2] : null;
        String subject = param.args.length > 6 && param.args[6] instanceof String ? (String) param.args[6] : null;
        String report = param.args.length > 7 && param.args[7] instanceof String ? (String) param.args[7] : null;

        File dataFile = null;
        ApplicationErrorReport.CrashInfo crashInfo = null;
        for (Object arg : param.args) {
            if (arg instanceof File) {
                dataFile = (File) arg;
            } else if (arg instanceof ApplicationErrorReport.CrashInfo) {
                crashInfo = (ApplicationErrorReport.CrashInfo) arg;
            }
        }

        String pkgName = resolvePackageName(processRecord, processName);
        if (pkgName == null || pkgName.isEmpty() || "android".equals(pkgName) || BuildConfig.APPLICATION_ID.equals(pkgName)) {
            return;
        }

        Context systemContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
        if (systemContext == null) return;

        String typePrefix = formatEventTypePrefix(eventType);
        String description;
        String stackTrace;

        if (crashInfo != null && crashInfo.stackTrace != null && !crashInfo.stackTrace.isEmpty()) {
            String excClass = crashInfo.exceptionClassName != null ? crashInfo.exceptionClassName : "Exception";
            String excMsg = crashInfo.exceptionMessage;
            String baseDesc = (excMsg != null && !excMsg.isEmpty()) ? (excClass + ": " + excMsg) : excClass;
            description = typePrefix.isEmpty() ? baseDesc : (typePrefix + " " + baseDesc);
            stackTrace = crashInfo.stackTrace;
            if (report != null && !report.isEmpty() && !stackTrace.contains(report)) {
                stackTrace = stackTrace + "\n\n--- System Report ---\n" + report;
            }
        } else {
            StringBuilder traceBuilder = new StringBuilder();
            if (subject != null && !subject.isEmpty()) {
                traceBuilder.append("Subject: ").append(subject).append("\n");
            }
            if (processName != null && !processName.isEmpty()) {
                traceBuilder.append("Process: ").append(processName).append("\n\n");
            }
            if (report != null && !report.isEmpty()) {
                traceBuilder.append(report).append("\n");
            }
            if (dataFile != null && dataFile.exists() && dataFile.canRead()) {
                String fileContent = readFilePreview(dataFile, 128 * 1024);
                if (!fileContent.isEmpty()) {
                    traceBuilder.append("\n--- File: ").append(dataFile.getName()).append(" ---\n");
                    traceBuilder.append(fileContent);
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

    private void handleAmsCrashInner(XC_MethodHook.MethodHookParam param) {
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

        if (crashInfo == null || crashInfo.stackTrace == null) {
            return;
        }

        String pkgName = resolvePackageName(processRecord, processName);
        if (pkgName == null || pkgName.isEmpty() || "android".equals(pkgName) || BuildConfig.APPLICATION_ID.equals(pkgName)) {
            return;
        }

        Context systemContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
        if (systemContext == null) {
            return;
        }

        String typePrefix = formatEventTypePrefix(eventType != null ? eventType : "crash");
        String excClass = crashInfo.exceptionClassName != null ? crashInfo.exceptionClassName : "Exception";
        String excMsg = crashInfo.exceptionMessage;
        String baseDesc = (excMsg != null && !excMsg.isEmpty())
                ? (excClass + ": " + excMsg)
                : excClass;
        String description = typePrefix.isEmpty() ? baseDesc : (typePrefix + " " + baseDesc);

        sendCrashBroadcastFromSystem(systemContext, pkgName, description, crashInfo.stackTrace);
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
        } else if (lower.contains("strictmode")) {
            return "[StrictMode]";
        }
        return "";
    }

    private static String extractSummaryDescription(String typePrefix, String subject, String stackTrace) {
        String prefix = typePrefix.isEmpty() ? "[Crash]" : typePrefix;
        // For native crash, look for "signal ... (SIG...)" or "Abort message:"
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

    private static synchronized boolean isDuplicateCrash(String pkgName, String description) {
        long now = System.currentTimeMillis();
        String key = pkgName + "|" + description;
        if (now - sLastCrashTime < 1500L && key.equals(sLastCrashKey)) {
            return true;
        }
        sLastCrashTime = now;
        sLastCrashKey = key;
        return false;
    }

    private static void sendCrashBroadcast(Context context, String pkgName, String description, String stackTrace) {
        if (isDuplicateCrash(pkgName, description)) return;
        Intent intent = new Intent(Intents.INTENT_ACTION)
                .setClassName(BuildConfig.APPLICATION_ID, CrashReceiver.class.getName())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Intents.INTENT_PACKAGE_NAME, pkgName)
                .putExtra(Intents.INTENT_TIME, System.currentTimeMillis())
                .putExtra(Intents.INTENT_DESCRIPTION, description)
                .putExtra(Intents.INTENT_STACKTRACE, stackTrace);
        context.sendBroadcast(intent);
    }

    private static void sendCrashBroadcastFromSystem(Context systemContext, String pkgName, String description, String stackTrace) {
        if (isDuplicateCrash(pkgName, description)) return;
        Intent intent = new Intent(Intents.INTENT_ACTION)
                .setClassName(BuildConfig.APPLICATION_ID, CrashReceiver.class.getName())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Intents.INTENT_PACKAGE_NAME, pkgName)
                .putExtra(Intents.INTENT_TIME, System.currentTimeMillis())
                .putExtra(Intents.INTENT_DESCRIPTION, description)
                .putExtra(Intents.INTENT_STACKTRACE, stackTrace);
        try {
            UserHandle userAll = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
            XposedHelpers.callMethod(systemContext, "sendBroadcastAsUser", intent, userAll);
        } catch (Throwable ignored) {
            systemContext.sendBroadcast(intent);
        }
    }

    private void hookUncaughtException(Class<?> clazz) {
        int i = 0;
        do { // Search through superclasses
            try {
                XposedHelpers.findAndHookMethod(
                        clazz,
                        "uncaughtException",
                        Thread.class,
                        Throwable.class,
                        uncaughtExceptionHook);
                Log.d("CapyCrash", "hookUncaughtException (" + mPkg
                        + "): Hooked class " + clazz.getName() + " after " + i + " loops");
                return;
            } catch (Throwable ignore) {
            }
            i++;
        } while ((clazz = clazz.getSuperclass()) != null);
    }
}
