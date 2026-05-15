package com.papi.nova.binding.input.capture

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.PointerIcon
import android.view.View

@TargetApi(Build.VERSION_CODES.N)
open class AndroidPointerIconCaptureProvider(
    activity: Activity,
    private val targetView: View,
) : InputCaptureProvider() {
    private val context: Context = activity

    override fun hideCursor() {
        super.hideCursor()
        targetView.pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }

    override fun showCursor() {
        super.showCursor()
        targetView.pointerIcon = null
    }

    companion object {
        @JvmStatic
        fun isCaptureProviderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}
