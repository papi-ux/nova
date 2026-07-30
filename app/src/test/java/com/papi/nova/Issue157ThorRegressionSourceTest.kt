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
        assertTrue(adapter.contains("setOnClickListener { serverActionListener?.invoke(obj) }"))
        assertTrue(layout.contains("@+id/server_actions_button"))
        assertTrue(layout.contains("android:layout_height=\"48dp\""))
        assertTrue(layout.contains("@+id/server_actions_label"))
        assertTrue(layout.contains("@drawable/nova_chip_default"))
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
        val dbReferenceIndex = removal.indexOf("getLocalDatabaseReference()")
        val tupleRemovalIndex = removal.indexOf("pollingTuples.remove(computer.uuid)")
        val lockDrainIndex = removal.indexOf("synchronized(removed.networkLock)")
        assertTrue(dbReferenceIndex >= 0)
        assertTrue(tupleRemovalIndex > dbReferenceIndex)
        assertTrue(lockDrainIndex > tupleRemovalIndex)
        assertTrue(service.contains("finally"))
        assertTrue(service.contains("releaseLocalDatabaseReference()"))
        assertTrue(viewModel.contains("fun removeComputer(uuid: String)"))
        assertTrue(pcView.contains("serverRemovalScope.launch"))
        assertTrue(pcView.contains("appContext.bindService("))
        assertTrue(pcView.contains("Context.BIND_AUTO_CREATE"))
        assertTrue(pcView.contains("appContext.unbindService(removalConnection)"))
        assertTrue(pcView.contains("Toast.makeText(appContext, failureMessage"))
        val pcRemoval = pcView.substringAfter("private fun removeComputer(details: ComputerDetails)")
            .substringBefore("private fun checkAutoNavigation")
        val preferenceCleanupIndex = pcRemoval.indexOf("appContext.getSharedPreferences")
        val modelCleanupIndex = pcRemoval.indexOf("removalViewModel?.removeComputer")
        val shortcutCleanupIndex = pcRemoval.indexOf("removalShortcutHelper.disableComputerShortcut")
        val activityGuardIndex = pcRemoval.indexOf("if (!isFinishing && !isDestroyed)")
        assertTrue(preferenceCleanupIndex >= 0)
        assertTrue(modelCleanupIndex > preferenceCleanupIndex)
        assertTrue(shortcutCleanupIndex > modelCleanupIndex)
        assertTrue(activityGuardIndex > shortcutCleanupIndex)
        assertFalse(pcRemoval.contains("lifecycleScope.launch"))
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
