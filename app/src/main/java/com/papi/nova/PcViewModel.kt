package com.papi.nova

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papi.nova.computers.ComputerManagerListener
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class PcViewModel : ViewModel() {
    private val _computers = MutableStateFlow<List<ComputerObject>>(emptyList())
    val computers: StateFlow<List<ComputerObject>> = _computers

    private val computerMap = ConcurrentHashMap<String, ComputerObject>()
    
    // For Java compatibility
    private val _computersLiveData = MutableLiveData<List<ComputerObject>>()
    val computersLiveData: LiveData<List<ComputerObject>> = _computersLiveData

    private val managerListener = object : ComputerManagerListener {
        override fun notifyComputerUpdated(details: ComputerDetails) {
            viewModelScope.launch {
                val obj = computerMap[details.uuid] ?: ComputerObject(details)
                obj.details = details
                computerMap[details.uuid] = obj
                
                val newList = computerMap.values.toList()
                _computers.value = newList
                _computersLiveData.postValue(newList)
            }
        }
    }

    fun startPolling(binder: ComputerManagerService.ComputerManagerBinder) {
        binder.startPolling(managerListener)
    }

    fun stopPolling(binder: ComputerManagerService.ComputerManagerBinder) {
        binder.stopPolling()
    }

    // Moved from PcView to avoid circular dependency
    class ComputerObject(@JvmField var details: ComputerDetails) {
        override fun toString(): String {
            return details.name
        }
        fun guessManagementUrl(): String? {
            val address = details.activeAddress ?: return null
            return "https://" + address.address + ":" + (details.guessExternalPort() + 1)
        }
    }
}
