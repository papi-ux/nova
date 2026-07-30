package com.papi.nova.grid

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.AppView
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaThemeManager
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
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.AppTheme,
        )
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
        val duplicateId = adapter.getItemId(0)

        assertEquals("duplicate logical hosts must only appear once", 1, adapter.itemCount)

        adapter.setItems(
            listOf(
                createComputerObject("duplicate-server"),
                createComputerObject("duplicate-server"),
            )
        )

        assertEquals("replacement duplicates must still normalize to one row", 1, adapter.itemCount)
        assertEquals("the normalized logical host must keep its stable ID", duplicateId, adapter.getItemId(0))

        val known = createComputerObject("known-server")
        adapter.setItems(listOf(known))
        val knownId = adapter.getItemId(0)
        val provisional = createComputerObject("").apply { details.uuid = "" }
        adapter.setItems(listOf(provisional))
        val provisionalId = adapter.getItemId(0)

        provisional.details.uuid = known.details.uuid
        adapter.setItems(listOf(provisional, known))

        assertEquals("discovery collisions must normalize to one logical host", 1, adapter.itemCount)
        assertEquals("the known UUID must retain ownership of its stable ID", knownId, adapter.getItemId(0))
        assertTrue("the provisional ID must not displace a known UUID", provisionalId != adapter.getItemId(0))
    }

    @Test
    fun pcGridAdapterKeepsStableIdsBoundToLogicalUuids() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val serverA = createComputerObject("server-a")
        val serverB = createComputerObject("server-b")
        adapter.setItems(listOf(serverA, serverB))
        val serverAId = adapter.getItemId(0)
        val serverBId = adapter.getItemId(1)

        serverA.details.uuid = "server-b-uuid"
        adapter.setItems(listOf(serverA, serverB))

        assertEquals("duplicate UUID normalization should retain one logical server", 1, adapter.itemCount)
        assertEquals("the retained logical server-b row must keep server-b's ID", serverBId, adapter.getItemId(0))

        val replacementA = createComputerObject("server-a")
        val replacementB = createComputerObject("server-b")
        adapter.setItems(listOf(replacementA, replacementB))

        assertEquals("server-a must retain its UUID-owned ID", serverAId, adapter.getItemId(0))
        assertEquals("server-b replacements must retain their UUID-owned ID", serverBId, adapter.getItemId(1))
        assertTrue("logical UUIDs must never alias the same stable ID", adapter.getItemId(0) != adapter.getItemId(1))
    }

    @Test
    fun pcGridAdapterTreatsNonblankUuidChangesAsNewLogicalServers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val original = createComputerObject("server-a")
        adapter.setItems(listOf(original))
        val serverAId = adapter.getItemId(0)

        original.details.uuid = "server-c-uuid"
        adapter.setItems(listOf(original))
        val serverCId = adapter.getItemId(0)

        assertTrue("a different nonblank UUID must not inherit server-a's ID", serverAId != serverCId)

        val replacementA = createComputerObject("server-a")
        adapter.setItems(listOf(replacementA, original))

        assertEquals(serverAId, adapter.getItemId(0))
        assertEquals(serverCId, adapter.getItemId(1))
    }

    @Test
    fun pcGridAdapterUsesActiveAccentOnlyForOnlineComputerIcon() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.AppTheme,
        )
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val computer = createComputerObject("accent-server")
        computer.details.state = ComputerDetails.State.ONLINE
        adapter.setItems(listOf(computer))

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
        }
        val holder = adapter.onCreateViewHolder(recyclerView, 0)
        adapter.onBindViewHolder(holder, 0)
        val icon = holder.itemView.findViewById<ImageView>(R.id.grid_image)

        assertEquals(
            NovaThemeManager.getAccentColor(context),
            ImageViewCompat.getImageTintList(icon)?.defaultColor,
        )

        computer.details.state = ComputerDetails.State.OFFLINE
        adapter.onBindViewHolder(holder, 0)
        assertEquals(
            NovaThemeManager.getTextSecondaryColor(context),
            ImageViewCompat.getImageTintList(icon)?.defaultColor,
        )
    }

    @Test
    fun pcGridAdapterKeepsManageActionDistinctFromPrimaryRowClick() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.AppTheme,
        )
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val computer = createComputerObject("managed-server")
        var opened: PcViewModel.ComputerObject? = null
        var managed: PcViewModel.ComputerObject? = null
        adapter.setOnItemClickListener { opened = it }
        adapter.setOnServerActionListener { managed = it }
        adapter.setItems(listOf(computer))

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
        }
        val holder = adapter.onCreateViewHolder(recyclerView, 0)
        adapter.onBindViewHolder(holder, 0)

        val manageAction = holder.itemView.findViewById<View>(R.id.server_actions_button)
        val manageLabel = holder.itemView.findViewById<View>(R.id.server_actions_label)
        assertTrue(manageAction.isActivated)
        assertTrue(manageLabel.isActivated)
        assertTrue(manageAction.performClick())
        assertEquals(computer, managed)
        assertNull(opened)

        assertTrue(holder.itemView.performClick())
        assertEquals(computer, opened)
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
