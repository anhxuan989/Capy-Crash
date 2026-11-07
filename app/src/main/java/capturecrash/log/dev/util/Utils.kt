@file:JvmName("Utils")

package capturecrash.log.dev.util

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.updateBounds
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import capturecrash.log.dev.R
import capturecrash.log.dev.core.data.crash.CrashLoader
import java.util.Locale
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

fun Context.getAttrColor(attr: Int): Int {
    val ta = this.obtainStyledAttributes(intArrayOf(attr))
    val colorAccent = ta.getColor(0, 0)
    ta.recycle()
    return colorAccent
}

/**
 * Copies the text to the clipboard.
 *
 * @param label  User-visible label for the clip data.
 * @param text   The actual text in the clip.
 */
private fun Context.copyToClipboard(label: CharSequence?, text: CharSequence?) {
    val clipboard = this.getSystemService<ClipboardManager>()
    val data = ClipData.newPlainText(label, text)
    clipboard?.setPrimaryClip(data)
}

fun Context.copyTextToClipboard(label: Int, pkg: String?, str: String?) {
    this.copyToClipboard(
        this.getString(
            label, CrashLoader.getAppName(this, pkg, false)
        ), str
    )
}

/**
 * Displays a toast to the viewer.
 *
 * @param string  The text displayed in the toast.
 */
fun Context.displayToast(@StringRes string: Int) {
    Toast.makeText(this, string, Toast.LENGTH_SHORT).show()
}

// Borrowed from https://github.com/K1rakishou/Kuroba-Experimental
@Suppress("ReplaceSizeCheckWithIsNotEmpty", "NOTHING_TO_INLINE")
@OptIn(ExperimentalContracts::class)
inline fun CharSequence?.isNeitherNullNorEmpty(): Boolean {
    contract {
        returns(true) implies (this@isNeitherNullNorEmpty != null)
    }

    return this != null && this.length > 0
}

fun Drawable.convertToBitmap(): Bitmap? {
    if (this is BitmapDrawable) {
        return this.bitmap
    }
    val width = if (this.intrinsicWidth > 0) this.intrinsicWidth else 1
    val height = if (this.intrinsicHeight > 0) this.intrinsicHeight else 1
    val bitmap = createBitmap(width, height)

    return bitmap.applyCanvas {
        updateBounds(
            right = this.width,
            bottom = this.height
        )
        draw(this)
    }
}

fun Context.openSystemNotificationSettings() {
    try {
        Intent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                action = "android.settings.APP_NOTIFICATION_SETTINGS"
                putExtra("app_package", packageName)
                putExtra("app_uid", applicationInfo.uid)
            }
        }.also(this::startActivity)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun setPendingIntentFlag(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
}

fun Context.getCompatColor(@ColorRes id: Int): Int {
    return ContextCompat.getColor(this, id)
}

fun Context.updateLocale() {
    if (PreferenceHelper.forceEnglish) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
    }
}

/**
 * Configures mandatory Edge-to-Edge layout & WindowInsets for Android 16 (API 36) & Android 17 (API 37).
 */
fun ComponentActivity.setupEdgeToEdge(
    toolbarView: View,
    contentView: View? = null,
    scrollingView: View? = null,
    cabOverlayView: View? = null
) {
    val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val bgColor = getCompatColor(R.color.mainBackground)
    val barStyle = if (isNight) {
        SystemBarStyle.dark(bgColor)
    } else {
        SystemBarStyle.light(bgColor, bgColor)
    }
    enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)

    val actionBarSize = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.actionBarSize)).let {
        val size = it.getDimensionPixelSize(0, 0)
        it.recycle()
        size
    }

    val initialScrollPaddingTop = scrollingView?.paddingTop ?: 0
    val initialScrollPaddingBottom = scrollingView?.paddingBottom ?: 0
    val initialScrollPaddingLeft = scrollingView?.paddingLeft ?: 0
    val initialScrollPaddingRight = scrollingView?.paddingRight ?: 0

    ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )

        toolbarView.updatePadding(
            top = insets.top,
            left = insets.left,
            right = insets.right
        )
        toolbarView.updateLayoutParams<ViewGroup.LayoutParams> {
            height = actionBarSize + insets.top
        }

        cabOverlayView?.updatePadding(
            top = insets.top,
            left = insets.left,
            right = insets.right
        )
        cabOverlayView?.updateLayoutParams<ViewGroup.LayoutParams> {
            height = actionBarSize + insets.top
        }

        contentView?.let { cv ->
            val lp = cv.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                if ((cv.parent as? android.widget.LinearLayout)?.orientation != android.widget.LinearLayout.VERTICAL) {
                    lp.topMargin = actionBarSize + insets.top
                }
                lp.leftMargin = insets.left
                lp.rightMargin = insets.right
                cv.layoutParams = lp
            }
        }

        if (scrollingView != null) {
            if (scrollingView is ViewGroup) {
                scrollingView.clipToPadding = false
            }
            scrollingView.updatePadding(
                top = initialScrollPaddingTop,
                left = initialScrollPaddingLeft + (if (contentView == null) insets.left else 0),
                right = initialScrollPaddingRight + (if (contentView == null) insets.right else 0),
                bottom = initialScrollPaddingBottom + insets.bottom
            )
        } else {
            contentView?.updatePadding(bottom = insets.bottom)
        }

        windowInsets
    }
}
