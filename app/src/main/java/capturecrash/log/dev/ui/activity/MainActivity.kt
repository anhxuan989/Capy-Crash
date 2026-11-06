package capturecrash.log.dev.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import capturecrash.log.dev.R
import capturecrash.log.dev.ScoopApplication
import capturecrash.log.dev.core.data.crash.Crash
import capturecrash.log.dev.core.data.crash.CrashLoader
import capturecrash.log.dev.core.db.createDatabaseInstance
import capturecrash.log.dev.core.db.deleteValues
import capturecrash.log.dev.core.db.deleteWhereIn
import capturecrash.log.dev.core.db.destroyDatabaseInstance
import capturecrash.log.dev.core.db.dropTable
import capturecrash.log.dev.databinding.ActivityMainBinding
import capturecrash.log.dev.ui.adapter.CrashAdapter
import capturecrash.log.dev.ui.helper.ToolbarElevationHelper
import capturecrash.log.dev.util.PreferenceHelper
import capturecrash.log.dev.util.initScoopService
import capturecrash.log.dev.util.isServiceActive
import capturecrash.log.dev.util.isShizukuAvailable
import capturecrash.log.dev.util.isShizukuPermissionGranted
import capturecrash.log.dev.util.setupEdgeToEdge
import capturecrash.log.dev.util.updateLocale
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity(), CrashAdapter.Listener, SearchView.OnQueryTextListener,
    SearchView.OnCloseListener {
    private val mLoader = CrashLoader()
    private var mCombineApps = false
    private var mHasCrash = false
    private var mHandler: Handler? = null
    private var mAdapter: CrashAdapter? = null
    private var mNoItemsScreen: View? = null
    private var mIsCabActive = false

    private var mDestroyed = false
    private var mCheckPending = true
    private var mIsAvailable = true
    private var mWasLoading = false
    private val mUpdateCheckerRunnable: Runnable = object : Runnable {
        override fun run() {
            if (sUpdateRequired) {
                sUpdateRequired = false
                if (mCombineApps) // It doesn't look right when there's suddenly a single crash of
                // a different app in the list
                    return
                if (sVisible && sNewCrash != null) {
                    mAdapter!!.addCrash(sNewCrash)
                    updateViewStates(false)
                    sNewCrash = null
                } else {
                    loadData()
                }
            }
            mHandler!!.postDelayed(this, UPDATE_DELAY.toLong())
        }
    }
    private lateinit var binding: ActivityMainBinding

    private val cabBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            mAdapter?.setSelectionEnabled(false)
        }
    }

    private val requestNotifPermissionLauncher = registerForActivityResult(RequestPermission()) { _ ->
        // No-op: user decided on notification permission
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                initScoopService {
                    checkAvailability()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        updateLocale()

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.mainToolbar.toolbar)

        setupEdgeToEdge(
            toolbarView = binding.mainToolbar.toolbar,
            contentView = binding.mainContentContainer,
            scrollingView = binding.mainCrashView,
            cabOverlayView = binding.mainCabToolbar
        )

        setupCabToolbar()
        onBackPressedDispatcher.addCallback(this, cabBackPressedCallback)

        requestNotificationPermissionIfNeeded()

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            if (isShizukuAvailable() && !isShizukuPermissionGranted()) {
                Shizuku.requestPermission(1001)
            }
        } catch (_: Throwable) {
        }

        mAdapter = CrashAdapter(this, this)
        binding.mainCrashView.adapter = mAdapter
        binding.mainCrashView.isGone = true
        ToolbarElevationHelper(binding.mainCrashView, binding.mainToolbar.toolbar)

        val i = intent
        mHasCrash = i.hasExtra(EXTRA_CRASH)
        if (mHasCrash) {
            val c: Crash? = IntentCompat.getParcelableExtra(i, EXTRA_CRASH, Crash::class.java)
            val crashes = ArrayList<Crash?>()
            crashes.add(c)
            c?.children?.let(crashes::addAll)
            mAdapter!!.setCrashes(crashes)
            supportActionBar?.title =
                CrashLoader.getAppName(this, c?.packageName, true)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        mCombineApps = PreferenceHelper.combineSameApps
        mAdapter!!.setCombineSameApps(!mHasCrash && mCombineApps)
        binding.mainCrashView.setReverseOrder(mHasCrash || !mCombineApps)

        createDatabaseInstance(this, "main")

        if (savedInstanceState == null) {
            sUpdateRequired = false
            if (!mHasCrash) {
                loadData()
            } else {
                updateViewStates(false)
            }
        } else {
            mAdapter!!.restoreInstanceState(savedInstanceState)
            updateViewStates(false)
        }
        mHandler = Handler(Looper.getMainLooper())

        initScoopService {
            checkAvailability()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupCabToolbar() {
        binding.mainCabToolbar.apply {
            setNavigationIcon(R.drawable.ic_close)
            setNavigationOnClickListener {
                mAdapter?.setSelectionEnabled(false)
            }
            inflateMenu(R.menu.menu_cab)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.menu_cab_delete) {
                    showDeletePrompt()
                }
                true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mAdapter!!.saveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        // Cheap way to instantly apply changes
        mAdapter!!.setSearchPackageName(
            this, PreferenceHelper.searchPackageName
        )
        sVisible = true
        mHandler!!.post(mUpdateCheckerRunnable)
    }

    public override fun onPause() {
        super.onPause()
        sVisible = false
        mHandler!!.removeCallbacks(mUpdateCheckerRunnable)
        if (isFinishing && !mHasCrash) {
            destroyDatabaseInstance("main")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        mDestroyed = true
    }

    private fun updateViewStates(loading: Boolean?) {
        var newLoading = loading
        if (newLoading == null) {
            newLoading = mWasLoading
        }
        mWasLoading = newLoading
        if (mCheckPending) {
            newLoading = true
        }
        val empty = mAdapter!!.isEmpty
        binding.mainProgressbar.isVisible = newLoading
        binding.mainCrashView.isGone = newLoading || empty || !mIsAvailable

        if (!newLoading && empty && mIsAvailable) {
            if (mNoItemsScreen == null) {
                mNoItemsScreen = binding.mainNoItemsStub.inflate()
            }
            mNoItemsScreen!!.isVisible = true
        } else if (mNoItemsScreen != null) {
            mNoItemsScreen!!.isGone = true
        }
        if (!mIsAvailable) {
            if (mNoItemsScreen == null) {
                mNoItemsScreen = binding.mainNoPermissionStub.inflate()
            }
        }
    }

    private fun loadData() {
        mAdapter!!.setSelectionEnabled(false)
        updateViewStates(true)
        mLoader.loadData(
            this,
            PreferenceHelper.combineSameStackTrace,
            PreferenceHelper.combineSameApps,
            PreferenceHelper.blacklistList
        )
    }

    fun onDataLoaded(data: ArrayList<Crash?>?) {
        mAdapter!!.setCrashes(data)
        updateViewStates(false)
    }

    private fun setCabActive(active: Boolean) {
        mIsCabActive = active
        cabBackPressedCallback.isEnabled = active
        binding.mainCabToolbar.isVisible = active
    }

    private val activityResultLauncher = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            loadData()
        }
    }

    override fun onCrashClicked(crash: Crash) {
        if (mCombineApps && !mHasCrash) {
            activityResultLauncher.launch(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_CRASH, crash)
            )
        } else {
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_CRASH, crash)
            )
        }
    }

    override fun onToggleSelectionMode(enabled: Boolean) {
        setCabActive(enabled)
    }

    override fun onItemSelected(count: Int) {
        binding.mainCabToolbar.setTitle(
            String.format(
                resources.getQuantityString(
                    R.plurals.items_selected_count, count
                ), count
            )
        )
    }

    private fun showDeletePrompt() {
        val items = mAdapter!!.selectedItems
        if (items.isEmpty()) {
            return
        }
        val content = String.format(
            resources.getQuantityString(R.plurals.delete_multiple_confirm, items.size),
            items.size
        )
        AlertDialog.Builder(this@MainActivity)
            .setMessage(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                deleteItems(items)
                mAdapter!!.setSelectionEnabled(false)
                setResult(RESULT_OK) // Reload overview when going back to reflect changes
                if (mHasCrash && mAdapter!!.isEmpty) {
                    finish() // Everything deleted, go back to overview
                } else {
                    updateViewStates(false)
                }
            }
            .show()
    }

    private fun deleteItems(items: ArrayList<Crash>) {
        for (c in items) {
            if (!mHasCrash && c.children != null) {
                for (cc in c.children) {
                    cc.hiddenIds?.let {
                        deleteWhereIn(*it.toTypedArray())
                    }
                    mAdapter!!.removeCrash(cc)
                }
                deleteValues(c.children)
            }
            c.hiddenIds?.let {
                deleteWhereIn(*it.toTypedArray())
            }
            deleteValues(listOf(c))
            mAdapter!!.removeCrash(c)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchItem = menu.findItem(R.id.menu_main_search)
        val searchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(this)
        searchView.setOnCloseListener(this)
        return true
    }

    override fun onClose(): Boolean {
        mAdapter!!.search(this, null)
        return false
    }

    override fun onQueryTextChange(newText: String): Boolean {
        mAdapter!!.search(this, newText)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_main_clear -> {
                AlertDialog.Builder(this)
                    .setMessage(R.string.dialog_clear_content)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        dropTable()
                        onDataLoaded(null)
                    }
                    .show()
                return true
            }
            R.id.menu_main_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun checkAvailability() {
        if (!mDestroyed) {
            mCheckPending = false
            mIsAvailable = isServiceActive || ScoopApplication.xposedActive()
            updateViewStates(null)
        }
    }

    companion object {
        private const val EXTRA_CRASH = "capturecrash.log.dev.EXTRA_CRASH"
        private const val UPDATE_DELAY = 200
        private var sUpdateRequired = false
        private var sVisible = false
        private var sNewCrash: Crash? = null

        fun requestUpdate(newCrash: Crash?) {
            sUpdateRequired = true
            if (sVisible) {
                sNewCrash = newCrash
            }
        }
    }
}
