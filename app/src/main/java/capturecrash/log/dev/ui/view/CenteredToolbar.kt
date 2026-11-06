package capturecrash.log.dev.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isGone
import capturecrash.log.dev.R

class CenteredToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.toolbarStyle
) : Toolbar(context, attrs, defStyleAttr) {

    private var mTitleTextView: AppCompatTextView? = null
    private var mSubtitleTextView: AppCompatTextView? = null
    private var mTitleText: CharSequence? = null
    private var mSubtitleText: CharSequence? = null

    private fun inflateTitle() {
        LayoutInflater.from(context).inflate(R.layout.toolbar_title, this)
        mTitleTextView = findViewById(R.id.toolbar_title)
        mSubtitleTextView = findViewById(R.id.toolbar_subtitle)
    }

    override fun setTitle(title: CharSequence) {
        if (title.isNotEmpty()) {
            if (mTitleTextView == null) {
                inflateTitle()
            }
        }
        mTitleTextView?.let {
            it.text = title
        }
        mTitleText = title
    }

    override fun getTitle(): CharSequence? {
        return mTitleText
    }

    override fun setSubtitle(subtitle: CharSequence) {
        if (subtitle.isNotEmpty()) {
            if (mSubtitleTextView == null) {
                inflateTitle()
            }
        }
        mSubtitleTextView?.isGone = subtitle.isEmpty()
        mSubtitleTextView?.let {
            it.text = subtitle
        }
        mSubtitleText = subtitle
    }

    override fun getSubtitle(): CharSequence? {
        return mSubtitleText
    }
}
