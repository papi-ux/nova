package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Issue157ThorRegressionSourceTest {
    private val root = File("src/main/java/com/papi/nova")

    @Test
    fun singleTaskGameRelaunchesWhenRequestedDisplayChanges() {
        val game = File(root, "Game.kt").readText()
        val newIntent = game.substringAfter("override fun onNewIntent").substringBefore("override fun onPause")

        assertTrue(newIntent.contains("requiresGameRecreation"))
        assertTrue(newIntent.contains("relaunchStream()"))
        assertTrue(game.contains("resolveStreamResolution"))
    }

    @Test
    fun serverGridHasOneOwnerAndDisablesPredictiveReattachAnimations() {
        val pcView = File(root, "PcView.kt").readText()
        val fragment = File(root, "ui/AdapterFragment.kt").readText()
        val callbacks = File(root, "ui/AdapterFragmentCallbacks.kt").readText()

        assertTrue(pcView.contains("serverGridView?.adapter = null"))
        assertTrue(pcView.contains("override fun releaseAbsListView"))
        assertTrue(pcView.contains("itemAnimator = null"))
        assertTrue(pcView.contains("NovaServerGridLayoutManager"))
        assertTrue(fragment.contains("override fun onDestroyView()"))
        assertTrue(fragment.contains("releaseAbsListView"))
        assertTrue(callbacks.contains("fun releaseAbsListView"))
    }

    @Test
    fun serverRowsExposeDiscoverableManageAction() {
        val pcView = File(root, "PcView.kt").readText()
        val adapter = File(root, "grid/PcGridAdapter.kt").readText()
        val layout = File("src/main/res/layout/pc_grid_item.xml").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(pcView.contains("setOnServerActionListener"))
        assertTrue(pcView.contains("showServerBottomSheet(computer)"))
        assertTrue(adapter.contains("serverActionListener"))
        assertTrue(layout.contains("androidx.appcompat.widget.AppCompatButton"))
        assertTrue(layout.contains("@+id/server_actions_button"))
        assertTrue(layout.contains("@string/nova_server_manage"))
        assertTrue(strings.contains("name=\"nova_server_manage\""))
    }

    @Test
    fun successfulRemovalUpdatesPersistenceAndAuthoritativeUiState() {
        val pcView = File(root, "PcView.kt").readText()
        val viewModel = File(root, "PcViewModel.kt").readText()
        val service = File(root, "computers/ComputerManagerService.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(service.contains("fun removeComputer(computer: ComputerDetails): Boolean"))
        assertTrue(service.contains("isCurrentPollingComputer(pollingTuples, details)"))
        val removal = service.substringAfter("fun removeComputer(computer: ComputerDetails): Boolean")
            .substringBefore("private fun pollComputerNow")
        assertTrue(removal.contains("pollingTuples.remove(computer.uuid)"))
        assertTrue(removal.contains("removed?.future?.cancel(true)"))
        assertTrue(removal.contains("synchronized(removed.networkLock)"))
        assertTrue(removal.contains("deletePersistedComputer()"))
        assertTrue(service.contains("finally"))
        assertTrue(service.contains("releaseLocalDatabaseReference()"))
        assertTrue(viewModel.contains("fun removeComputer(uuid: String)"))
        assertTrue(pcView.contains("withContext(Dispatchers.IO)"))
        assertTrue(pcView.contains("binder.removeComputer(details)"))
        assertTrue(pcView.contains("viewModel.removeComputer(details.uuid)"))
        assertFalse(pcView.contains("binder == null || !binder.removeComputer(details)"))
        assertTrue(strings.contains("name=\"nova_server_remove_failed\""))
    }

    @Test
    fun manualServerAddHandlesRuntimeFailuresWithoutCrashingWorker() {
        val source = File(root, "preferences/AddComputerManually.kt").readText()

        assertTrue(source.contains("catch (e: RuntimeException)"))
        assertTrue(source.contains("managerBinder?.addComputerBlocking(details) == true"))
        assertTrue(source.contains("runCatching {\n                MoonBridge.testClientConnectivity"))
        assertTrue(source.contains("serviceBound = bindService("))
        assertTrue(source.contains("thread.join(500L)"))
        assertTrue(source.contains("if (serviceBound)"))
        val deepLinkConfirm = source.substringAfter("setPositiveButton(getString(R.string.proceed)").substringBefore("setNegativeButton")
        assertFalse(deepLinkConfirm.contains("finish()"))
        assertFalse(source.contains("managerBinder!!.addComputerBlocking"))
        assertFalse(source.contains("thread.join()"))
    }
}
