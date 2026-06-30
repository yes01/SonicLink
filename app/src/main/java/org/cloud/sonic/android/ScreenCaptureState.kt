package org.cloud.sonic.android

import android.content.Intent

object ScreenCaptureState {
    @Volatile
    var resultCode: Int = 0

    @Volatile
    var data: Intent? = null

    @Volatile
    var consumed: Boolean = false

    val hasPermission: Boolean
        get() = resultCode != 0 && data != null && !consumed

    fun grant(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.data = data
        consumed = false
    }

    fun markConsumed() {
        consumed = true
    }

    fun clear() {
        resultCode = 0
        data = null
        consumed = false
    }
}
