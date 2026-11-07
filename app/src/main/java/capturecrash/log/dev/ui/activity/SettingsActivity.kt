package capturecrash.log.dev.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import capturecrash.log.dev.R
import capturecrash.log.dev.ScoopApplication
import capturecrash.log.dev.databinding.ActivitySettingsBinding
import capturecrash.log.dev.util.isRootAvailable
import capturecrash.log.dev.util.isShizukuPermissionGranted
import capturecrash.log.dev.util.openSystemNotificationSettings
import capturecrash.log.dev.util.readLogsPermissionGranted
import capturecrash.log.dev.util.setupEdgeToEdge

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.settingsToolbar.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupEdgeToEdge(
            toolbarView = binding.settingsToolbar.toolbar,
            contentView = binding.settingsContainer
        )

        supportFragmentManager.commit {
            replace<SettingsFragment>(binding.settingsContainer.id)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.preferences)

            val notifSettingsPref = findPreference(R.string.prefKey_notif_sys_settings)
            notifSettingsPref?.setOnPreferenceClickListener {
                requireContext().openSystemNotificationSettings()
                true
            }

            val mutedAppsPref = findPreference(R.string.prefKey_muted_apps)
            mutedAppsPref?.intent = Intent(activity, BlacklistAppsActivity::class.java)
                .putExtra(BlacklistAppsActivity.EXTRA_MUTE_MODE, true)

            val blacklistedAppsPref = findPreference(R.string.prefKey_blacklisted_apps)
            blacklistedAppsPref?.setActivityIntent(BlacklistAppsActivity::class.java)

            val permissionStatusPref = findPreference(R.string.prefKey_permission_status)
            val hasPrivilege = requireContext().readLogsPermissionGranted() ||
                isShizukuPermissionGranted() ||
                isRootAvailable() ||
                ScoopApplication.xposedActive()
            permissionStatusPref?.summary =
                if (hasPrivilege) {
                    getString(R.string.settings_permission_status_summary_true)
                } else {
                    getString(R.string.settings_permission_status_summary_false)
                }

            val aboutPref = findPreference(R.string.prefKey_about_scoop)
            aboutPref?.setActivityIntent(AboutActivity::class.java)
        }

        private fun Preference.setActivityIntent(clazz: Class<*>) {
            intent = Intent(activity, clazz)
        }

        private fun findPreference(@StringRes key: Int): Preference? {
            return findPreference(getString(key))
        }
    }
}
