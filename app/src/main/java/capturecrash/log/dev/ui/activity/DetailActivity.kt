package capturecrash.log.dev.ui.activity

import android.os.Bundle
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ShareCompat
import androidx.core.content.IntentCompat
import androidx.core.text.toSpannable
import androidx.core.view.isGone
import androidx.core.view.isVisible
import capturecrash.log.dev.R
import capturecrash.log.dev.core.data.crash.Crash
import capturecrash.log.dev.core.data.crash.CrashLoader
import capturecrash.log.dev.core.db.createDatabaseInstance
import capturecrash.log.dev.core.db.selectById
import capturecrash.log.dev.databinding.ActivityDetailBinding
import capturecrash.log.dev.util.PreferenceHelper
import capturecrash.log.dev.util.copyTextToClipboard
import capturecrash.log.dev.util.displayToast
import capturecrash.log.dev.util.getCompatColor
import capturecrash.log.dev.util.isNeitherNullNorEmpty
import capturecrash.log.dev.util.setupEdgeToEdge
import java.util.Locale

class DetailActivity : AppCompatActivity(), SearchView.OnQueryTextListener,
    SearchView.OnCloseListener {

    private var mCrash: Crash? = null
    private var mHighlightColor = 0
    private var mSelectionEnabled = false
    private lateinit var binding: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.detailToolbar.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupEdgeToEdge(
            toolbarView = binding.detailToolbar.toolbar,
            contentView = binding.detailContentContainer,
            scrollingView = binding.detailScrollView
        )

        mHighlightColor = getCompatColor(R.color.highlightColor)
        val crashId = intent.getLongExtra(EXTRA_CRASH_ID, -1L)
        if (crashId > 0L) {
            createDatabaseInstance(this, "main")
            mCrash = selectById(crashId)
        }
        if (mCrash == null) {
            mCrash = try {
                IntentCompat.getParcelableExtra(intent, EXTRA_CRASH, Crash::class.java)
            } catch (_: Throwable) {
                null
            }
        }
        if (mCrash == null) {
            finish()
            return
        }
        supportActionBar?.title = CrashLoader.getAppName(this, mCrash!!.packageName, true)

        val trace = mCrash!!.stackTrace ?: ""
        mCrash!!.stackTrace = trace
        binding.detailCrashLogText.text = trace
        binding.detailCrashEdit.setText(trace)
        binding.detailCrashEdit.setTextSize(
            TypedValue.COMPLEX_UNIT_PX, binding.detailCrashLogText.textSize
        )

        binding.detailScrollView.setCropHorizontally(PreferenceHelper.autoWrap)
    }

    private fun highlightText(text: String?) {
        val rawTrace = mCrash?.stackTrace ?: return
        var newText = text
        val stackTrace = rawTrace.lowercase(Locale.ENGLISH)
        val span = rawTrace.toSpannable()
        if (newText.isNeitherNullNorEmpty()) {
            newText = newText.lowercase(Locale.ENGLISH) // Ignore case
            val size = newText.length
            var index = 0
            while (stackTrace.indexOf(newText, index).also { index = it } != -1) {
                span.setSpan(
                    BackgroundColorSpan(mHighlightColor),
                    index,
                    index + size,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                index += size
            }
        }
        binding.detailCrashLogText.setText(span, TextView.BufferType.SPANNABLE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        val searchItem = menu.findItem(R.id.menu_detail_search)
        val searchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(this)
        searchView.setOnCloseListener(this)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val pkg = mCrash?.packageName
        val hasPkg = !pkg.isNullOrEmpty()
        menu.findItem(R.id.menu_detail_mute_notif)?.apply {
            isVisible = hasPkg
            if (hasPkg) {
                val isMuted = PreferenceHelper.isAppMuted(pkg)
                setTitle(if (isMuted) R.string.action_unmute_notif else R.string.action_mute_notif)
                setIcon(if (isMuted) R.drawable.ic_notifications else R.drawable.ic_notifications_off)
            }
        }
        menu.findItem(R.id.menu_detail_blacklist)?.apply {
            isVisible = hasPkg
            if (hasPkg) {
                val isBlacklisted = PreferenceHelper.isAppBlacklisted(pkg)
                setTitle(if (isBlacklisted) R.string.action_unblacklist_app else R.string.action_blacklist_app)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onClose(): Boolean {
        highlightText(null)
        return false
    }

    override fun onQueryTextChange(newText: String): Boolean {
        highlightText(newText)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.menu_detail_select -> {
                mSelectionEnabled = !mSelectionEnabled
                item.setIcon(
                    if (mSelectionEnabled) {
                        R.drawable.ic_unselect
                    } else {
                        R.drawable.ic_select
                    }
                )
                // When one is visible, the other isn't (and vice versa)
                binding.detailCrashEdit.isVisible = mSelectionEnabled
                binding.detailScrollView.isGone = mSelectionEnabled
            }
            R.id.menu_detail_copy -> {
                copyTextToClipboard(
                    R.string.copy_label,
                    mCrash!!.packageName,
                    mCrash!!.stackTrace
                )
                displayToast(R.string.copied_toast)
                return true
            }
            R.id.menu_detail_share -> {
                val intent = ShareCompat.IntentBuilder(this)
                    .setType("text/plain")
                    .setText(mCrash!!.stackTrace)
                    .setChooserTitle(R.string.action_share)
                    .createChooserIntent()
                startActivity(intent)
                return true
            }
            R.id.menu_detail_mute_notif -> {
                val pkg = mCrash?.packageName ?: return true
                val appName = CrashLoader.getAppName(this, pkg, false)
                val nowMuted = PreferenceHelper.toggleMutePackage(pkg)
                val msgRes = if (nowMuted) R.string.notif_muted_toast else R.string.notif_unmuted_toast
                Toast.makeText(this, getString(msgRes, appName), Toast.LENGTH_SHORT).show()
                invalidateOptionsMenu()
                return true
            }
            R.id.menu_detail_blacklist -> {
                val pkg = mCrash?.packageName ?: return true
                val appName = CrashLoader.getAppName(this, pkg, false)
                val nowBlacklisted = PreferenceHelper.toggleBlacklistPackage(pkg)
                val msgRes = if (nowBlacklisted) R.string.app_blacklisted_toast else R.string.app_unblacklisted_toast
                Toast.makeText(this, getString(msgRes, appName), Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                invalidateOptionsMenu()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_CRASH = "crash"
        const val EXTRA_CRASH_ID = "crash_id"
    }
}
