package org.cloud.sonic.android.accessibility

data class SonicLinkControlResult(
    val success: Boolean,
    val code: String,
    val message: String
) {
    companion object {
        fun success(message: String = "ok"): SonicLinkControlResult {
            return SonicLinkControlResult(true, "ok", message)
        }

        fun failure(code: String, message: String): SonicLinkControlResult {
            return SonicLinkControlResult(false, code, message)
        }
    }
}
