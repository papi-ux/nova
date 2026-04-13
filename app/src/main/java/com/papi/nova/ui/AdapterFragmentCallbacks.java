package com.papi.nova.ui;

import android.view.View;

public interface AdapterFragmentCallbacks {
    int getAdapterFragmentLayoutId();
    void receiveAbsListView(View gridView);
}
