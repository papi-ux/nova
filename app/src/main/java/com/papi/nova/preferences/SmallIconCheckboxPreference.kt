package com.papi.nova.preferences

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.preference.CheckBoxPreference

class SmallIconCheckboxPreference : CheckBoxPreference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return PreferenceConfiguration.getDefaultSmallMode(context)
    }
}
