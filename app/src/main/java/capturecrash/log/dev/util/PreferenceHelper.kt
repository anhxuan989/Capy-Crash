package capturecrash.log.dev.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import capturecrash.log.dev.R

object PreferenceHelper {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var mRes: Resources

    fun Context.initPreferences() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        mRes = this.resources
    }

    private fun getKey(@StringRes key: Int): String {
        return mRes.getString(key)
    }

    private fun getSharedBoolean(@StringRes key: Int, defValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(getKey(key), defValue)
    }

    val showNotifications: Boolean
        get() = getSharedBoolean(R.string.prefKey_show_notification, true)

    val showActionButtons: Boolean
        get() = getSharedBoolean(R.string.prefKey_show_action_buttons, true)

    val showStackTraceNotifications: Boolean
        get() = getSharedBoolean(R.string.prefKey_show_stack_trace_notif, false)

    val combineSameApps: Boolean
        get() = getSharedBoolean(R.string.prefKey_combine_same_apps, false)

    val combineSameStackTrace: Boolean
        get() = getSharedBoolean(R.string.prefKey_combine_same_stack_trace, true)

    val searchPackageName: Boolean
        get() = getSharedBoolean(R.string.prefKey_search_package_name, true)

    val autoWrap: Boolean
        get() = getSharedBoolean(R.string.prefKey_auto_wrap, false)

    val autostartOnBoot: Boolean
        get() = getSharedBoolean(R.string.prefKey_autostart_on_boot, false)

    val ignoreThreadDeath: Boolean
        get() = getSharedBoolean(R.string.prefKey_ignore_threaddeath, true)

    val forceEnglish: Boolean
        get() = getSharedBoolean(R.string.prefKey_force_english, false)

    private val blacklistedPackages: String?
        get() = sharedPreferences.getString(
            getKey(R.string.key_blacklisted_packages), ""
        )

    val blacklistList: List<String>
        get() = blacklistedPackages
            ?.split(",".toRegex())
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun editBlacklistPackages(packages: List<String>) {
        sharedPreferences.edit {
            putString(
                getKey(R.string.key_blacklisted_packages),
                packages.filter { it.isNotEmpty() }.distinct().joinToString(",")
            )
        }
    }

    fun isAppBlacklisted(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return blacklistList.contains(packageName)
    }

    fun toggleBlacklistPackage(packageName: String): Boolean {
        val current = blacklistList.toMutableList()
        val nowBlacklisted = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        editBlacklistPackages(current)
        return nowBlacklisted
    }

    private val mutedPackages: String?
        get() = sharedPreferences.getString(
            getKey(R.string.key_muted_packages), ""
        )

    val mutedList: List<String>
        get() = mutedPackages
            ?.split(",".toRegex())
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun editMutedPackages(packages: List<String>) {
        sharedPreferences.edit {
            putString(
                getKey(R.string.key_muted_packages),
                packages.filter { it.isNotEmpty() }.distinct().joinToString(",")
            )
        }
    }

    fun isAppMuted(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return mutedList.contains(packageName)
    }

    fun toggleMutePackage(packageName: String): Boolean {
        val current = mutedList.toMutableList()
        val nowMuted = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        editMutedPackages(current)
        return nowMuted
    }
}
