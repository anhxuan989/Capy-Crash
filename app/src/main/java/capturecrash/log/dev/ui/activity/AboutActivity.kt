package capturecrash.log.dev.ui.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import capturecrash.log.dev.R
import capturecrash.log.dev.databinding.ActivityAboutBinding
import capturecrash.log.dev.util.setupEdgeToEdge

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.aboutToolbar.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupEdgeToEdge(
            toolbarView = binding.aboutToolbar.toolbar,
            contentView = binding.aboutContent
        )

        binding.aboutCredits.text = getString(
            R.string.about_author,
            "@MrWasdennnoch (XDA), @paphonb (XDA), @TacoTheDank (GitHub)"
        )

        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            binding.aboutVersion.text = String.format(
                getString(R.string.about_version),
                pInfo.versionName,
                PackageInfoCompat.getLongVersionCode(pInfo)
            )
        } catch (e: PackageManager.NameNotFoundException) {
            // No.
        }

        binding.aboutGithub.setOnClickListener {
            val i = Intent(
                Intent.ACTION_VIEW,
                "https://github.com/TacoTheDank/Scoop".toUri()
            )
            startActivity(i)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
