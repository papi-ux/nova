package com.papi.nova.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView
import kotlin.math.min
import kotlin.math.roundToInt

/** Leaves computers room on a short portrait screen while every supporting action can scroll. */
class NovaHostsNavigationScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screen = resources.configuration.screenHeightDp * resources.displayMetrics.density
        val bound = (screen * .34f).roundToInt().coerceAtLeast(1)
        val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED }
            ?: bound
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(min(bound, available), MeasureSpec.AT_MOST))
    }
}
