# Capy Crash

Catches and records stack traces when apps crash unexpectedly on Android.

[<img src="images/get-it-on-github.png"
      alt="Get it on GitHub"
      height="80">](https://github.com/anhxuan989/Capy-Crash/releases)

**Capy Crash** captures and saves stack traces of crashing applications and displays them in a clean, searchable list so you no longer need to dig through long, noisy `logcat` outputs.

Whether you are debugging your own apps or diagnosing issues on your device, Capy Crash supports **Xposed / LSPosed**, **Root**, **Shizuku**, and **ADB** across modern Android versions.

---

## Features

- **Comprehensive Crash Detection**: Captures Java/Kotlin uncaught exceptions, Native C/C++ crashes (`SIGSEGV`, `SIGABRT`, tombstones), ANRs (Application Not Responding), and system `WTF` errors.
- **Multiple Operating Modes**: Works with **LSPosed / Xposed**, **Root (`libsu`)**, **Shizuku**, or **ADB**.
- **Instant Crash Notifications**: Configurable notifications with stack trace previews and quick actions to **Copy** or **Share** logs immediately.
- **Smart Organization**: Combine identical stack traces or group crashes by app to keep your crash list tidy.
- **Search & Filter**: Search by app name, package name, or stack trace content, and blacklist noisy apps you want to ignore.
- **Modern Android Support**: Built for Android 7.0 (API 24) up to Android 16+ (API 36), with full compatibility for Android 14, 15, and 16+ restrictions.

---

## Compatibility Changes for Android 14+

Android 14 (API 34), Android 15 (API 35), and Android 16 (API 36) introduced strict security, background execution, and permission changes. Capy Crash has been heavily updated to work seamlessly on modern Android releases:

- **Bypassing Per-App `logcat` Approval Prompts (Android 13–16+)**: Android's `LogcatManagerService` blocks continuous background `READ_LOGS` access or repeatedly shows system confirmation dialogs. Capy Crash now opens privileged `logcat -b main,system,crash` streams directly via **Root (`su`)** or **Shizuku (shell UID 2000)** when available, preventing background log interruptions.
- **System Server Hooking for LSPosed / Xposed (`android` Scope)**: In addition to app-level exception hooks, Capy Crash hooks `ActivityManagerService` (`addErrorToDropBox` and `handleApplicationCrashInner`) in the Android System Framework. This reliably captures global Java crashes, Native C/C++ crashes, and ANRs across all apps without needing a background logcat service.
- **Android 14+ Foreground Service Compliance**: Updated `CrashDetectorService` and `IndicatorService` with `FOREGROUND_SERVICE_SPECIAL_USE` permission and mandatory `specialUse` foreground service subtype declarations required by Android 14+.
- **Android 14+ Broadcast & Receiver Security**: Added explicit `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` flags, explicit package/component targeting for crash broadcasts, and `UserHandle.ALL` system broadcast delivery.
- **Background Activity & Service Exemptions**: Automatically configures `SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION` via Root or Shizuku and safely handles `ForegroundServiceStartNotAllowedException`.
- **Android 15/16+ UI & Core Modernization**:
  - Full **Edge-to-Edge** display and `WindowInsets` support required when targeting SDK 35/36.
  - **Predictive Back** gesture support (`OnBackInvokedCallback` & `OnBackPressedDispatcher`).
  - Runtime `POST_NOTIFICATIONS` permission handling and modern `PackageInfoFlags` / `IntentCompat` APIs.
  - Replaced deprecated third-party database/CAB libraries with a fast, thread-safe native `SQLiteOpenHelper` implementation and Material contextual action bar.

---

## Setup Guide

Capy Crash needs permission to intercept crashes or read system crash logs. Choose **one** of the following setup methods depending on your device setup:

### Method 1: LSPosed / Xposed (Recommended for Rooted Devices)

Using LSPosed requires no background logcat polling service and catches Java crashes, Native crashes, and ANRs directly from the system server.

1. Install **Capy Crash** and open your **LSPosed Manager**.
2. Enable the **Capy Crash** module.
3. Make sure the following scopes are checked (selected by default):
   - **System Framework (`android`)** — *Required to catch crashes globally across all apps.*
   - **Capy Crash (`capturecrash.log.dev`)** — *Required so the app detects that the module is active.*
   - *(Optional)* Select specific target apps if you only want per-app thread hooks instead of hooking System Framework.
4. Reboot your device (or soft-reboot / restart scopes as prompted by LSPosed).

### Method 2: Root Access (Without Xposed)

1. Install and open **Capy Crash**.
2. Grant **Root (Superuser)** permission when prompted by Magisk, KernelSU, or APatch.
3. Capy Crash will automatically grant itself the required permissions (`READ_LOGS`, `POST_NOTIFICATIONS`, and background exemptions) and start monitoring crashes via a privileged root logcat stream.

### Method 3: Shizuku (Recommended for Non-Rooted Devices)

Shizuku allows Capy Crash to grant its own permissions and read crash logs with ADB shell privileges directly on the device—without needing a PC every time and without triggering Android 14+ logcat permission popups.

1. Install and start **[Shizuku](https://shizuku.rikka.app/)** (via Wireless Debugging on Android 11+ or via a PC).
2. Open **Capy Crash**.
3. An authorization prompt from Shizuku will appear—tap **Allow all the time**.
4. Capy Crash will automatically configure `READ_LOGS` and start capturing crashes immediately.

### Method 4: ADB via Computer (Non-Rooted Devices)

If you do not use Root or Shizuku, you can grant the `READ_LOGS` permission manually using `adb` from a computer:

1. Enable **Developer Options** and **USB Debugging** on your Android device.
2. Connect your device to your computer and run:
   ```sh
   adb shell pm grant capturecrash.log.dev android.permission.READ_LOGS
   ```
3. *(Optional, recommended for Android 14+)* Allow background start exemption:
   ```sh
   adb shell appops set capturecrash.log.dev SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION allow
   ```
4. Force stop and reopen **Capy Crash** for the changes to take effect.

---

## Contributing

Contributions, bug reports, and feature requests are welcome! Feel free to open an issue or submit a pull request if you want to help improve Capy Crash.

---

## License

```
Copyright (C) 2017 Adrian Paschkowski

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
