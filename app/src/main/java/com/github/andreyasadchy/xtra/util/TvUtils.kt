package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import com.github.andreyasadchy.xtra.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

object TvUtils {

    private var isTvCache: Boolean? = null

    fun isTv(context: Context): Boolean {
        return isTvCache ?: run {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
                .also { isTvCache = it }
        }
    }

    /**
     * Highlights the focused view so D-pad navigation is visible.
     * Cards get a colored stroke and a small zoom, other views get a rounded border drawn as foreground.
     */
    fun installFocusHighlight(activity: Activity) {
        val root = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            oldFocus?.let { removeHighlight(it) }
            newFocus?.let { addHighlight(it) }
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
    }

    private fun dp(view: View, value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, view.resources.displayMetrics)
    }

    private fun addHighlight(view: View) {
        if (view is EditText) return
        val color = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
        if (view is MaterialCardView) {
            view.setTag(R.id.tv_focus_original_stroke_width, view.strokeWidth)
            view.setTag(R.id.tv_focus_original_stroke_color, view.strokeColorStateList)
            view.strokeWidth = dp(view, 3f).toInt()
            view.setStrokeColor(ColorStateList.valueOf(color))
            view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120L).start()
            view.translationZ = dp(view, 4f)
        } else {
            view.setTag(R.id.tv_focus_original_foreground, view.foreground ?: NO_FOREGROUND)
            view.foreground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(view, 8f)
                setStroke(dp(view, 3f).toInt(), color)
                setColor(MaterialColors.compositeARGBWithAlpha(color, 0x33))
            }
        }
    }

    private fun removeHighlight(view: View) {
        if (view is MaterialCardView) {
            (view.getTag(R.id.tv_focus_original_stroke_width) as? Int)?.let { view.strokeWidth = it }
            (view.getTag(R.id.tv_focus_original_stroke_color) as? ColorStateList)?.let { view.setStrokeColor(it) }
            view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            view.translationZ = 0f
        } else {
            when (val original = view.getTag(R.id.tv_focus_original_foreground)) {
                NO_FOREGROUND -> view.foreground = null
                is Drawable -> view.foreground = original
            }
            view.setTag(R.id.tv_focus_original_foreground, null)
        }
    }

    private val NO_FOREGROUND = Any()
}
