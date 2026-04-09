package com.papi.nova.ui;

import android.widget.AbsListView;

public interface AdapterFragmentCallbacks {
    int getAdapterFragmentLayoutId();
    void receiveAbsListView(AbsListView gridView);
}
