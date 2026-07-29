package com.papi.nova.grid

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.AppView
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.preferences.PreferenceConfiguration
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinGridAdaptersMigrationTest {
    @Test
    fun appGridAdapterIsKotlinSource() {
        val names = arrayOf(
            "AppGridAdapter",
            "GenericGridAdapter",
            "PcGridAdapter",
            "RecyclerItemClickListener"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/grid/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/grid/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun appGridAdapterKeepsJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!

        AppGridAdapter::class.java.getConstructor(
            Context::class.java,
            PreferenceConfiguration::class.java,
            ComputerDetails::class.java,
            String::class.java,
            booleanType
        )
        AppGridAdapter::class.java.getMethod("filterByName", String::class.java)
        assertEquals(intType, AppGridAdapter::class.java.getMethod("getTotalAppCount").returnType)
        AppGridAdapter::class.java.getMethod("updateHiddenApps", Set::class.java, booleanType)
        AppGridAdapter::class.java.getMethod("updateLayoutWithPreferences", Context::class.java, PreferenceConfiguration::class.java)
        AppGridAdapter::class.java.getMethod("cancelQueuedOperations")
        AppGridAdapter::class.java.getMethod("updatePinnedApps", Set::class.java)
        assertEquals(booleanType, AppGridAdapter::class.java.getMethod("isAppPinned", intType).returnType)
        AppGridAdapter::class.java.getMethod("addApp", AppView.AppObject::class.java)
        AppGridAdapter::class.java.getMethod("removeApp", AppView.AppObject::class.java)
        AppGridAdapter::class.java.getMethod("clear")
        AppGridAdapter::class.java.getMethod("populateFeaturedArt", AppView.AppObject::class.java, ImageView::class.java)
        AppGridAdapter::class.java.getMethod(
            "populateView",
            View::class.java,
            ImageView::class.java,
            RelativeLayout::class.java,
            ProgressBar::class.java,
            TextView::class.java,
            ImageView::class.java,
            AppView.AppObject::class.java
        )
    }

    @Test
    fun pcGridAdapterKeepsJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!

        PcGridAdapter::class.java.getConstructor(Context::class.java, PreferenceConfiguration::class.java)
        PcGridAdapter::class.java.getMethod("updateLayoutWithPreferences", Context::class.java, PreferenceConfiguration::class.java)
        PcGridAdapter::class.java.getMethod("addComputer", PcViewModel.ComputerObject::class.java)
        assertEquals(
            booleanType,
            PcGridAdapter::class.java.getMethod("removeComputer", PcViewModel.ComputerObject::class.java).returnType
        )
        PcGridAdapter::class.java.getMethod(
            "populateView",
            View::class.java,
            ImageView::class.java,
            RelativeLayout::class.java,
            ProgressBar::class.java,
            TextView::class.java,
            ImageView::class.java,
            PcViewModel.ComputerObject::class.java
        )
    }

    @Test
    fun recyclerItemClickListenerKeepsJavaCompatibleApis() {
        val intType = Int::class.javaPrimitiveType!!

        assertTrue(RecyclerView.OnItemTouchListener::class.java.isAssignableFrom(RecyclerItemClickListener::class.java))
        RecyclerItemClickListener::class.java.getConstructor(
            Context::class.java,
            RecyclerView::class.java,
            RecyclerItemClickListener.OnItemClickListener::class.java
        )
        RecyclerItemClickListener.OnItemClickListener::class.java.getMethod("onItemClick", View::class.java, intType)
        RecyclerItemClickListener.OnItemClickListener::class.java.getMethod("onLongItemClick", View::class.java, intType)
    }

    @Test
    fun genericGridAdapterKeepsJavaCompatibleApis() {
        val intType = Int::class.javaPrimitiveType!!
        val longType = Long::class.javaPrimitiveType!!
        val constructor = GenericGridAdapter::class.java.getDeclaredConstructor(Context::class.java, intType)
        assertFalse(Modifier.isPrivate(constructor.modifiers))

        GenericGridAdapter::class.java.getField("itemList")
        GenericGridAdapter::class.java.getMethod("setOnItemClickListener", GenericGridAdapter.OnItemClickListener::class.java)
        GenericGridAdapter::class.java.getMethod("setItems", List::class.java)
        GenericGridAdapter::class.java.getDeclaredMethod("setLayoutId", intType)
        GenericGridAdapter::class.java.getMethod("clear")
        assertEquals(intType, GenericGridAdapter::class.java.getMethod("getItemCount").returnType)
        assertEquals(Any::class.java, GenericGridAdapter::class.java.getMethod("getItem", intType).returnType)
        assertEquals(longType, GenericGridAdapter::class.java.getMethod("getItemId", intType).returnType)
        GenericGridAdapter.ViewHolder::class.java.getConstructor(View::class.java)
        GenericGridAdapter.ViewHolder::class.java.getField("imgView")
        GenericGridAdapter.ViewHolder::class.java.getField("gridMask")
        GenericGridAdapter.ViewHolder::class.java.getField("overlayView")
        GenericGridAdapter.ViewHolder::class.java.getField("txtView")
        GenericGridAdapter.ViewHolder::class.java.getField("prgView")
    }

    @Test
    fun genericGridViewHolderAllowsLayoutsWithoutGridMask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = LayoutInflater.from(context).inflate(R.layout.pc_grid_item, null, false)

        val holder = GenericGridAdapter.ViewHolder(view)

        assertNull(holder.gridMask)
    }

    @Test
    fun appGridAdapterKeepsPinnedStateWithoutItems() {
        val adapter = createAdapter()

        assertEquals(0, adapter.getTotalAppCount())
        assertFalse(adapter.isAppPinned(42))

        adapter.updatePinnedApps(setOf(42))

        assertTrue(adapter.isAppPinned(42))
        assertFalse(adapter.isAppPinned(99))
    }

    @Test
    fun pcGridAdapterSortsComputersByNameAndRemovesItems() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val beta = createComputerObject("server-beta")
        val alpha = createComputerObject("server-alpha")

        adapter.addComputer(beta)
        adapter.addComputer(alpha)

        assertEquals(alpha, adapter.getItem(0))
        assertEquals(beta, adapter.getItem(1))
        val betaId = adapter.getItemId(1)
        assertTrue(adapter.removeComputer(alpha))
        assertEquals(beta, adapter.getItem(0))
        assertEquals(betaId, adapter.getItemId(0))
        assertFalse(adapter.removeComputer(alpha))

        adapter.clear()
        assertEquals(0, adapter.itemCount)
        adapter.addComputer(beta)
        assertEquals(betaId, adapter.getItemId(0))
        assertTrue(adapter.removeComputer(beta))
        assertEquals(0, adapter.itemCount)
    }

    @Test
    fun pcGridAdapterRefreshesKnownServersWithoutReplacingWholeList() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val alpha = createComputerObject("server-alpha")
        val beta = createComputerObject("server-beta")
        adapter.setItems(listOf(alpha, beta))

        var wholeListRefreshes = 0
        var changedItems = 0
        var payloadChanges = 0
        adapter.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    wholeListRefreshes++
                }

                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                    payload: Any?,
                ) {
                    changedItems += itemCount
                    if (payload != null) {
                        payloadChanges += itemCount
                    }
                }
            }
        )

        val refreshedAlpha = createComputerObject("server-alpha").apply {
            details.state = ComputerDetails.State.ONLINE
        }
        val refreshedBeta = createComputerObject("server-beta").apply {
            details.state = ComputerDetails.State.OFFLINE
        }
        adapter.setItems(listOf(refreshedAlpha, refreshedBeta))

        assertEquals("poll refresh should not replace every server row", 0, wholeListRefreshes)
        assertEquals("retained rows should still rebind their latest status", 2, changedItems)
        assertEquals("poll refresh should reuse existing focused holders", 2, payloadChanges)
        assertEquals(refreshedAlpha, adapter.getItem(0))
        assertEquals(refreshedBeta, adapter.getItem(1))
    }

    @Test
    fun pcGridAdapterUsesServerIdentityForStableIds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val alpha = createComputerObject("server-alpha")
        val beta = createComputerObject("server-beta")
        adapter.setItems(listOf(alpha, beta))
        val alphaId = adapter.getItemId(0)
        val betaId = adapter.getItemId(1)

        val refreshedBeta = createComputerObject("server-beta")
        val refreshedAlpha = createComputerObject("server-alpha")
        adapter.setItems(listOf(refreshedBeta, refreshedAlpha))

        assertTrue("server rows should advertise stable IDs", adapter.hasStableIds())
        assertEquals(betaId, adapter.getItemId(0))
        assertEquals(alphaId, adapter.getItemId(1))
    }

    @Test
    fun pcGridAdapterKeepsFallbackIdsUniqueAndStable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val firstBlank = createComputerObject("").apply {
            details.uuid = ""
            details.name = "Duplicate"
        }
        val secondBlank = createComputerObject("").apply {
            details.uuid = ""
            details.name = "Duplicate"
        }
        adapter.setItems(listOf(firstBlank, secondBlank))
        val firstBlankId = adapter.getItemId(0)
        val secondBlankId = adapter.getItemId(1)

        assertTrue("blank UUID rows must still have unique IDs", firstBlankId != secondBlankId)

        firstBlank.details.name = "Renamed"
        firstBlank.details.uuid = "discovered-server"
        adapter.setItems(listOf(firstBlank, secondBlank))

        assertEquals("a retained provisional row must keep its ID after discovery", firstBlankId, adapter.getItemId(0))
        assertEquals(secondBlankId, adapter.getItemId(1))

        val repeatedBlank = createComputerObject("").apply { details.uuid = "" }
        adapter.setItems(listOf(repeatedBlank, repeatedBlank))
        val repeatedBlankId = adapter.getItemId(0)

        assertEquals("the same row object must only appear once", 1, adapter.itemCount)

        adapter.setItems(listOf(repeatedBlank, repeatedBlank))

        assertEquals(repeatedBlankId, adapter.getItemId(0))

        val firstDuplicate = createComputerObject("duplicate-server")
        val secondDuplicate = createComputerObject("duplicate-server")
        adapter.setItems(listOf(firstDuplicate, secondDuplicate))

        assertEquals("duplicate logical hosts must only appear once", 1, adapter.itemCount)
    }

    @Test
    fun pcGridAdapterTransfersUuidOwnershipWithoutAliasingIds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val retained = createComputerObject("server-a")
        adapter.setItems(listOf(retained))
        val retainedId = adapter.getItemId(0)

        retained.details.uuid = "server-b"
        adapter.setItems(listOf(retained))

        assertEquals("the retained row should keep its ID after UUID discovery", retainedId, adapter.getItemId(0))

        val replacementForOldUuid = createComputerObject("server-a")
        adapter.setItems(listOf(replacementForOldUuid, retained))

        assertTrue(
            "the old UUID must not remain aliased to the retained row ID",
            adapter.getItemId(0) != retainedId,
        )
        assertEquals(retainedId, adapter.getItemId(1))
    }

    private fun createAdapter(): AppGridAdapter {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceConfiguration()
        val computer = ComputerDetails()
        computer.uuid = "adapter-test-computer"
        return AppGridAdapter(context, prefs, computer, "adapter-test", false)
    }

    private fun createComputerObject(name: String): PcViewModel.ComputerObject {
        val details = ComputerDetails()
        details.uuid = "$name-uuid"
        details.name = name
        return PcViewModel.ComputerObject(details)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
