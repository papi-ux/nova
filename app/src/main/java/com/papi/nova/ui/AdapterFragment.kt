package com.papi.nova.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.papi.nova.R

class AdapterFragment : Fragment() {
    private lateinit var callbacks: AdapterFragmentCallbacks

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as AdapterFragmentCallbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(callbacks.getAdapterFragmentLayoutId(), container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        callbacks.receiveAbsListView(view.findViewById(R.id.fragmentView))
    }

    override fun onDestroyView() {
        view?.findViewById<View>(R.id.fragmentView)?.let(callbacks::releaseAbsListView)
        super.onDestroyView()
    }
}
