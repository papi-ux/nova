package com.papi.nova.ui

import android.view.View

interface AdapterFragmentCallbacks {
    fun getAdapterFragmentLayoutId(): Int

    fun receiveAbsListView(gridView: View)
}
