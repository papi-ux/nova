package com.papi.nova.ui

import com.papi.nova.preferences.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NovaPlaySetupDeviceEditsTest {
    private val resolution = NovaSettingDefinition("list_resolution", "Resolution", "", "", NovaSettingType.Select)
    private val fps = NovaSettingDefinition("list_fps", "Frame rate", "", "", NovaSettingType.Select)
    private var pending: (suspend () -> Unit)? = null
    private val stored = linkedMapOf<String, NovaSettingValue>()
    private var checks = 0
    private fun edits() = NovaPlaySetupDeviceEdits(
        save = { definition, value, completed -> stored[definition.key] = value; completed() },
        settle = { pending = it }, outcome = { NovaTierSaveResult.SAVED }, recheck = { checks++ },
    )

    @Test fun resolutionAndFpsBeforeSettleBothPersist() = runBlocking {
        val edits = edits()
        edits.change(resolution, NovaSettingValue.StringValue("3840x2160"))
        edits.change(fps, NovaSettingValue.StringValue("120"))
        pending!!.invoke()
        assertEquals(NovaSettingValue.StringValue("3840x2160"), stored[resolution.key])
        assertEquals(NovaSettingValue.StringValue("120"), stored[fps.key])
        assertEquals(1, checks)
    }

    @Test fun unrelatedHostWorkCannotReplaceTheLocalSave() = runBlocking {
        edits().change(resolution, NovaSettingValue.StringValue("3840x2160"))
        pending = { checks++ }
        pending!!.invoke()
        assertEquals(NovaSettingValue.StringValue("3840x2160"), stored[resolution.key])
    }

    @Test fun repeatedPressesReadTheLatestLocalChoiceBeforeSettle() = runBlocking {
        val edits = edits()
        val sizes = listOf("1280x720", "1920x1080", "2560x1440", "3840x2160")
        stored[resolution.key] = NovaSettingValue.StringValue(sizes.first())
        repeat(3) {
            val current = (stored[resolution.key] as NovaSettingValue.StringValue).value
            edits.change(resolution, NovaSettingValue.StringValue(sizes[sizes.indexOf(current) + 1]))
        }
        pending!!.invoke()
        assertEquals(NovaSettingValue.StringValue("3840x2160"), stored[resolution.key])
    }
}
