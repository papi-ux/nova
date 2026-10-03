package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.manager.NovaStreamSource
import com.papi.nova.manager.NovaStreamSourceLine
import org.junit.Assert.*
import org.junit.Test

class NovaHostCopyRecoveryAuthorityTest {
    private val saved = NovaStreamSourceLine(NovaStreamSource.HOST_SAVED_COPY,"Host's saved copy · 1280×720",100000,
        listOf("host_bitrate_cap"))
    private val writable = PolarisClientSettings(capabilities=PolarisClientSettings.Capabilities(
        displayModeOverride=true,targetBitrateOverride=true))
    private fun row(source:NovaStreamSourceLine?=saved, settings:PolarisClientSettings?=writable,
        space:Boolean=false,watch:Boolean=false,metered:Boolean=false,checking:Boolean=false,busy:Boolean=false,
        current:()->Boolean={true},write:()->Unit={}) = novaHostCopyRecovery(source,settings,space,watch,metered,
            checking,busy,current,write)
    @Test fun onlyTypedStaleCopyCanOfferRecoveryAndCapsRemainUnchanged() {
        for(source in NovaStreamSource.entries.filter { it!=NovaStreamSource.HOST_SAVED_COPY }) {
            assertNull(row(saved.copy(source=source)))
        }
        assertNull(row(null));assertNull(row(space=true));assertNull(row(watch=true));assertNull(row(metered=true))
        assertTrue(row()!!.enabled)
        assertEquals(100000,saved.capKbps);assertEquals(listOf("host_bitrate_cap"),saved.limitCodes)
    }
    @Test fun HostPermissionsBusyAndRecheckProtectActualCallbacks() {
        var writes=0
        for(disabled in listOf(row(settings=null,write={writes++}),row(settings=PolarisClientSettings(),write={writes++}),
            row(checking=true,write={writes++}),row(busy=true,write={writes++}))) {
            assertFalse(disabled!!.enabled);disabled.onUseDeviceSetting()
        }
        assertEquals(0,writes)
        var current=true
        val old=row(current={current},write={writes++})!!
        current=false;old.onUseDeviceSetting();assertEquals("stale host/plan cannot write",0,writes)
        current=true;old.onUseDeviceSetting();assertEquals(1,writes)
    }
}
