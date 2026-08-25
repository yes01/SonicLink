package org.cloud.sonic.android.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

fun View.applySystemBarMargins() {
    val initialMargins = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val initialLeftMargin = initialMargins.leftMargin
    val initialRightMargin = initialMargins.rightMargin
    val initialBottomMargin = initialMargins.bottomMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val insetTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val systemBarInsets = insets.getInsets(insetTypes)
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            leftMargin = initialLeftMargin + systemBarInsets.left
            rightMargin = initialRightMargin + systemBarInsets.right
            bottomMargin = initialBottomMargin + systemBarInsets.bottom
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
