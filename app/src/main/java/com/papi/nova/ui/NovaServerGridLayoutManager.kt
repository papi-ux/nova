package com.papi.nova.ui

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager

/**
 * Server rows are rebound continuously as discovery state changes. Predictive pre-layout can try to
 * reattach a row that an OEM multi-display transition has not detached yet, so this list uses a
 * deterministic one-pass layout instead.
 */
class NovaServerGridLayoutManager(context: Context) : GridLayoutManager(context, 1) {
    override fun supportsPredictiveItemAnimations(): Boolean = false
}
