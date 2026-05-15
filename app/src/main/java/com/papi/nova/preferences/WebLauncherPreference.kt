package com.papi.nova.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import com.papi.nova.utils.HelpLauncher

class WebLauncherPreference : Preference {
    private lateinit var url: String

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes) {
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initialize(attrs)
    }

    private fun initialize(attrs: AttributeSet?) {
        checkNotNull(attrs) { "WebLauncherPreference must have attributes!" }
        url = attrs.getAttributeValue(null, "url")
            ?: throw IllegalStateException("WebLauncherPreference must have 'url' attribute!")
    }

    public override fun onClick() {
        HelpLauncher.launchUrl(context, url)
    }
}
