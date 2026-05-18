package com.papi.nova.ui

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

class NovaFocusDrawableTest {
    @Test
    fun cardFocusRingUsesCleanHighContrastStroke() {
        val doc = parseXml("src/main/res/drawable/nova_card_focus_ring.xml")

        assertTrue(
            "focus ring should be a clean shape",
            doc.documentElement.tagName == "shape"
        )
        assertTrue(
            "focus ring should include a clear nova_accent stroke",
            hasStroke(doc, "3dp", "@color/nova_accent")
        )
    }

    @Test
    fun chipFocusedStatesUseCleanHighContrastStroke() {
        assertFocusedStroke("src/main/res/drawable/nova_chip_default.xml", "3dp", "@color/nova_accent")
        assertFocusedStroke("src/main/res/drawable/nova_chip_selected.xml", "3dp", "@color/nova_ice")
    }

    @Test
    fun serverRowsUseFocusableOutlineBackground() {
        val doc = parseXml("src/main/res/layout/pc_grid_item.xml")

        assertTrue(
            "server row root should carry the D-pad focus outline",
            doc.documentElement.getAttribute("android:background") == "@drawable/nova_card_focus_frame"
        )

        val frame = parseXml("src/main/res/drawable/nova_card_focus_frame.xml")
        assertTrue(
            "server row focus frame should use the row-specific filled ring",
            hasFocusedDrawable(frame, "@drawable/nova_server_row_focus_ring")
        )

        val rowRing = parseXml("src/main/res/drawable/nova_server_row_focus_ring.xml")
        assertTrue(
            "server row focus ring should use a slimmer accent stroke",
            hasStroke(rowRing, "2dp", "@color/nova_accent")
        )
    }

    @Test
    fun appViewAndServerCardResourcesStayScopedFromComposeLibraryChanges() {
        val dimens = parseXml("src/main/res/values/dimens.xml")
        val landDimens = parseXml("src/main/res/values-land/dimens.xml")
        val ripple = parseXml("src/main/res/drawable/nova_ripple_accent.xml")

        assertTrue(
            "AppView search height should keep the established portrait height",
            hasDimen(dimens, "nova_search_height", "48dp")
        )
        assertTrue(
            "shared card radius should remain available for XML View surfaces",
            hasDimen(dimens, "nova_card_corner_radius", "14dp")
        )
        assertTrue(
            "large shared card radius should remain available for XML View surfaces",
            hasDimen(dimens, "nova_card_corner_radius_lg", "16dp")
        )
        assertTrue(
            "landscape game cover height resource should remain available for XML View surfaces",
            hasDimen(landDimens, "nova_game_card_cover_height", "180dp")
        )
        assertTrue(
            "server card ripple should continue to follow the shared card radius",
            hasCorners(ripple, "@dimen/nova_card_corner_radius")
        )
    }

    @Test
    fun serverGridDefersFocusToRows() {
        val doc = parseXml("src/main/res/layout/pc_grid_view.xml")

        assertTrue(
            "server grid should pass focus to its row children",
            doc.documentElement.getAttribute("android:descendantFocusability") == "afterDescendants"
        )
    }

    @Test
    fun serverFilterChipsNavigateDownToServerFocusBridge() {
        val layouts = arrayOf(
            "src/main/res/layout/activity_pc_view.xml",
            "src/main/res/layout-land/activity_pc_view.xml"
        )

        for (layout in layouts) {
            val doc = parseXml(layout)

            assertTrue(
                "$layout All filter should navigate down to host list",
                hasViewAttribute(doc, "filterAllServers", "android:nextFocusDown", "@id/serverListFocusBridge")
            )
            assertTrue(
                "$layout Online filter should navigate down to host list",
                hasViewAttribute(doc, "filterOnlineServers", "android:nextFocusDown", "@id/serverListFocusBridge")
            )
            assertTrue(
                "$layout Streaming filter should navigate down to host list",
                hasViewAttribute(doc, "filterStreamingServers", "android:nextFocusDown", "@id/serverListFocusBridge")
            )
            assertTrue(
                "$layout Needs Pairing filter should navigate down to host list",
                hasViewAttribute(doc, "filterNeedsPairingServers", "android:nextFocusDown", "@id/serverListFocusBridge")
            )
            assertTrue(
                "$layout server focus bridge should be a concrete focus target below filters",
                hasViewAttribute(doc, "serverListFocusBridge", "android:focusable", "true")
            )
        }
    }

    @Test
    fun materialServerChipsExposeFocusedStroke() {
        val doc = parseXml("src/main/res/values/styles.xml")

        assertTrue(
            "Material server chips should use the focused stroke selector",
            hasStyleItem(doc, "NovaMaterialChip", "chipStrokeColor", "@color/nova_focus_stroke_selector")
        )
        assertTrue(
            "Material server chips should reserve stroke width for focus",
            hasStyleItem(doc, "NovaMaterialChip", "chipStrokeWidth", "2dp")
        )
    }

    @Test
    fun dashboardQuickActionsUseFocusAwareChipBackground() {
        val doc = parseXml("src/main/res/values/styles.xml")

        assertTrue(
            "Theme and Help quick actions should use the chip drawable with a focused outline",
            hasStyleItem(doc, "NovaFilterChip", "android:background", "@drawable/nova_chip_default")
        )
    }

    @Test
    fun dashboardGithubActionsUseExplicitGithubLabel() {
        val layouts = arrayOf(
            "src/main/res/layout/activity_pc_view.xml",
            "src/main/res/layout-land/activity_pc_view.xml"
        )

        for (layout in layouts) {
            val doc = parseXml(layout)

            assertTrue(
                "$layout top support action should say GitHub because it opens Nova GitHub",
                hasViewAttribute(doc, "actionHelp", "android:text", "@string/pcview_quick_github")
            )
            assertTrue(
                "$layout empty-state support action should say GitHub because it opens Nova GitHub",
                hasViewAttribute(doc, "emptyHelp", "android:text", "@string/pcview_quick_github")
            )
        }
    }

    @Test
    fun dashboardTopIconActionsExposeFocusedLabel() {
        val layouts = arrayOf(
            "src/main/res/layout/activity_pc_view.xml",
            "src/main/res/layout-land/activity_pc_view.xml"
        )
        val source = readSource("src/main/java/com/papi/nova/PcView.kt")

        for (layout in layouts) {
            val doc = parseXml(layout)

            assertTrue(
                "$layout should include one stable label for focused top icon actions",
                hasViewAttribute(doc, "topActionFocusLabel", "android:visibility", "invisible")
            )
        }
        assertTrue(
            "PcView should bind profile/sync/settings focus to the label",
            source.contains("bindTopActionFocusLabel(") &&
                source.contains("R.id.topActionFocusLabel") &&
                source.contains("R.string.pcview_quick_profiles") &&
                source.contains("R.string.pcview_quick_polaris_sync") &&
                source.contains("R.string.pcview_quick_settings")
        )
    }

    @Test
    fun dashboardPolarisStartupActionUsesLaunchIcon() {
        val layouts = arrayOf(
            "src/main/res/layout/activity_pc_view.xml",
            "src/main/res/layout-land/activity_pc_view.xml"
        )
        val source = readSource("src/main/java/com/papi/nova/PcView.kt")

        for (layout in layouts) {
            val doc = parseXml(layout)

            assertTrue(
                "$layout Polaris startup action should look like launch/start, not bidirectional sync",
                hasViewAttribute(doc, "actionPolarisSync", "app:icon", "@drawable/ic_play")
            )
            assertTrue(
                "$layout Polaris startup action should keep the Start Polaris label",
                hasViewAttribute(doc, "actionPolarisSync", "android:contentDescription", "@string/pcview_quick_polaris_sync")
            )
        }
        assertTrue(
            "Top action should still launch the Polaris startup flow",
            source.contains("polarisSyncAction?.setOnClickListener { launchPolarisStartupForPreferredHost() }")
        )
    }

    @Test
    fun dashboardThemeActionOpensPickerInsteadOfBlindCycle() {
        val source = readSource("src/main/java/com/papi/nova/PcView.kt")

        assertTrue(
            "Theme quick action should open a picker rather than immediately cycling themes",
            source.contains("themeAction?.setOnClickListener { v ->") &&
                source.contains("showThemePicker(v)") &&
                source.contains("private fun showThemePicker(") &&
                source.contains("NovaThemeManager.setTheme(this, theme)")
        )
    }

    @Test
    fun serverRowsExposeStateHintsAndFocusedPrimaryAction() {
        val row = parseXml("src/main/res/layout/pc_grid_item.xml")
        val adapter = readSource("src/main/java/com/papi/nova/grid/PcGridAdapter.kt")
        val genericAdapter = readSource("src/main/java/com/papi/nova/grid/GenericGridAdapter.kt")

        assertTrue(
            "server rows should reserve a persistent hint line for offline/pairing/Polaris states",
            hasViewAttribute(row, "status_hint_text", "android:visibility", "gone")
        )
        assertTrue(
            "server row primary action should use a focus-aware chip background",
            hasViewAttribute(row, "primary_action_text", "android:background", "@drawable/nova_chip_default")
        )
        assertTrue(
            "server rows should update the action chip selected state from row focus",
            genericAdapter.contains("open fun onItemFocusChanged(") &&
                genericAdapter.contains("onItemFocusChanged(holder.itemView, hasFocus)") &&
                adapter.contains("override fun onItemFocusChanged(") &&
                adapter.contains("primaryAction?.isSelected = hasFocus")
        )
        assertTrue(
            "server rows should explain the state-specific action, not only name the state",
            adapter.contains("R.string.pcview_card_hint_pair") &&
                adapter.contains("R.string.pcview_card_hint_open_library") &&
                adapter.contains("R.string.pcview_card_hint_wake") &&
                adapter.contains("R.string.pcview_card_hint_checking_library")
        )
    }

    @Test
    fun appGridAdapterTogglesGameCardFocusRing() {
        val source = readSource("src/main/java/com/papi/nova/grid/GenericGridAdapter.kt")

        assertTrue(
            "game grid items should show their overlay focus ring when focused",
            source.contains("R.id.nova_focus_ring") &&
                source.contains("setOnFocusChangeListener") &&
                source.contains("focusRing?.visibility = if (hasFocus) View.VISIBLE else View.GONE")
        )
    }

    @Test
    fun serverFiltersUseRuntimeDownFocusBridge() {
        val source = readSource("src/main/java/com/papi/nova/PcView.kt")

        assertTrue(
            "server filters should bind DPAD down to the first visible host row",
            source.contains("bindServerFilterFocusDown(filterAllServers") &&
                source.contains("override fun dispatchKeyEvent(event: KeyEvent): Boolean") &&
                source.contains("KeyEvent.KEYCODE_DPAD_DOWN") &&
                source.contains("serverListFocusBridge?.setOnFocusChangeListener") &&
                source.contains("addOnGlobalFocusChangeListener") &&
                source.contains("setHeaderQuickActionsFocusable(false)") &&
                source.contains("setServerFilterNextFocusDown(firstRow") &&
                source.contains("setNextFocusDownId") &&
                source.contains("moveFocusToFirstServerRow()")
        )
    }

    private fun assertFocusedStroke(path: String, width: String, color: String) {
        val doc = parseXml(path)

        assertTrue(
            "$path should include a clean focused stroke",
            hasStroke(doc, width, color)
        )
    }

    private fun parseXml(path: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(File(path))
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun hasStroke(doc: Document, width: String, color: String): Boolean =
        doc.elements("stroke").any { stroke ->
            width == stroke.getAttribute("android:width") &&
                color == stroke.getAttribute("android:color")
        }

    private fun hasCorners(doc: Document, radius: String): Boolean =
        doc.elements("corners").any { corner ->
            radius == corner.getAttribute("android:radius")
        }

    private fun hasDimen(doc: Document, name: String, value: String): Boolean =
        doc.elements("dimen").any { dimen ->
            name == dimen.getAttribute("name") && value == dimen.textContent
        }

    private fun hasFocusedDrawable(doc: Document, drawable: String): Boolean =
        doc.elements("item").any { item ->
            item.getAttribute("android:state_focused") == "true" &&
                drawable == item.getAttribute("android:drawable")
        }

    private fun hasStyleItem(doc: Document, styleName: String, itemName: String, value: String): Boolean =
        doc.elements("style")
            .filter { style -> styleName == style.getAttribute("name") }
            .flatMap { style -> style.getElementsByTagName("item").asElementSequence() }
            .any { item -> itemName == item.getAttribute("name") && value == item.textContent }

    private fun hasViewAttribute(doc: Document, idName: String, attrName: String, value: String): Boolean {
        val idValue = "@+id/$idName"
        return doc.elements("*").any { node ->
            idValue == node.getAttribute("android:id") && value == node.getAttribute(attrName)
        }
    }

    private fun Document.elements(tagName: String): Sequence<Element> =
        getElementsByTagName(tagName).asElementSequence()

    private fun NodeList.asElementSequence(): Sequence<Element> =
        (0 until length).asSequence().map { index -> item(index) as Element }
}
