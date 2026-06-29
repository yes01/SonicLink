package org.cloud.sonic.android

import android.content.Intent

object ScreenCaptureState {
    @Volatile
    var resultCode: Int = 0

    @Volatile
    var data: Intent? = null

    val hasPermission: Boolean
        get() = resultCode != 0 && data != null
}
