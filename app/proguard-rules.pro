# Add project specific ProGuard rules here.

-dontobfuscate

-keep class capturecrash.log.dev.core.xposed.XposedHook
-keep class capturecrash.log.dev.ScoopApplication { *; }
-keep class androidx.appcompat.widget.SearchView { *; }
-keep class capturecrash.log.dev.core.data.crash.Crash { *; }

-keep class de.robv.android.xposed.** { *; }
