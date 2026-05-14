package com.papi.nova.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

public class NovaFocusDrawableTest {
    @Test
    public void cardFocusRingUsesCleanHighContrastStroke() throws Exception {
        Document doc = parseDrawable("src/main/res/drawable/nova_card_focus_ring.xml");

        assertTrue("focus ring should be a clean shape",
                doc.getDocumentElement().getTagName().equals("shape"));
        assertTrue("focus ring should include a clear nova_accent stroke",
                hasStroke(doc, "3dp", "@color/nova_accent"));
    }

    @Test
    public void chipFocusedStatesUseCleanHighContrastStroke() throws Exception {
        assertFocusedStroke("src/main/res/drawable/nova_chip_default.xml", "3dp", "@color/nova_accent");
        assertFocusedStroke("src/main/res/drawable/nova_chip_selected.xml", "3dp", "@color/nova_ice");
    }

    @Test
    public void serverRowsUseFocusableOutlineBackground() throws Exception {
        Document doc = parseDrawable("src/main/res/layout/pc_grid_item.xml");

        assertTrue("server row root should carry the D-pad focus outline",
                "@drawable/nova_card_focus_frame".equals(doc.getDocumentElement().getAttribute("android:background")));

        Document frame = parseDrawable("src/main/res/drawable/nova_card_focus_frame.xml");
        assertTrue("server row focus frame should use the row-specific filled ring",
                hasFocusedDrawable(frame, "@drawable/nova_server_row_focus_ring"));

        Document rowRing = parseDrawable("src/main/res/drawable/nova_server_row_focus_ring.xml");
        assertTrue("server row focus ring should use a slimmer accent stroke",
                hasStroke(rowRing, "2dp", "@color/nova_accent"));
    }

    @Test
    public void serverGridDefersFocusToRows() throws Exception {
        Document doc = parseDrawable("src/main/res/layout/pc_grid_view.xml");

        assertTrue("server grid should pass focus to its row children",
                "afterDescendants".equals(doc.getDocumentElement().getAttribute("android:descendantFocusability")));
    }

    @Test
    public void serverFilterChipsNavigateDownToServerFocusBridge() throws Exception {
        String[] layouts = {
                "src/main/res/layout/activity_pc_view.xml",
                "src/main/res/layout-land/activity_pc_view.xml"
        };

        for (String layout : layouts) {
            Document doc = parseDrawable(layout);

            assertTrue(layout + " All filter should navigate down to host list",
                    hasViewAttribute(doc, "filterAllServers", "android:nextFocusDown", "@id/serverListFocusBridge"));
            assertTrue(layout + " Online filter should navigate down to host list",
                    hasViewAttribute(doc, "filterOnlineServers", "android:nextFocusDown", "@id/serverListFocusBridge"));
            assertTrue(layout + " Streaming filter should navigate down to host list",
                    hasViewAttribute(doc, "filterStreamingServers", "android:nextFocusDown", "@id/serverListFocusBridge"));
            assertTrue(layout + " Needs Pairing filter should navigate down to host list",
                    hasViewAttribute(doc, "filterNeedsPairingServers", "android:nextFocusDown", "@id/serverListFocusBridge"));
            assertTrue(layout + " server focus bridge should be a concrete focus target below filters",
                    hasViewAttribute(doc, "serverListFocusBridge", "android:focusable", "true"));
        }
    }

    @Test
    public void materialServerChipsExposeFocusedStroke() throws Exception {
        Document doc = parseDrawable("src/main/res/values/styles.xml");

        assertTrue("Material server chips should use the focused stroke selector",
                hasStyleItem(doc, "NovaMaterialChip", "chipStrokeColor", "@color/nova_focus_stroke_selector"));
        assertTrue("Material server chips should reserve stroke width for focus",
                hasStyleItem(doc, "NovaMaterialChip", "chipStrokeWidth", "2dp"));
    }

    @Test
    public void serverFiltersUseRuntimeDownFocusBridge() throws Exception {
        String source = new String(
                Files.readAllBytes(Paths.get("src/main/java/com/papi/nova/PcView.java")),
                StandardCharsets.UTF_8);

        assertTrue("server filters should bind DPAD down to the first visible host row",
                source.contains("bindServerFilterFocusDown(filterAllServers")
                        && source.contains("boolean dispatchKeyEvent(KeyEvent event)")
                        && source.contains("KeyEvent.KEYCODE_DPAD_DOWN")
                        && source.contains("serverListFocusBridge.setOnFocusChangeListener")
                        && source.contains("addOnGlobalFocusChangeListener")
                        && source.contains("setHeaderQuickActionsFocusable(false)")
                        && source.contains("setServerFilterNextFocusDown(firstRow")
                        && source.contains("setNextFocusDownId")
                        && source.contains("moveFocusToFirstServerRow()"));
    }

    private static void assertFocusedStroke(String path, String width, String color) throws Exception {
        Document doc = parseDrawable(path);

        assertTrue(path + " should include a clean focused stroke",
                hasStroke(doc, width, color));
    }

    private static Document parseDrawable(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

    private static boolean hasStroke(Document doc, String width, String color) {
        NodeList strokes = doc.getElementsByTagName("stroke");
        for (int i = 0; i < strokes.getLength(); i++) {
            org.w3c.dom.Element stroke = (org.w3c.dom.Element) strokes.item(i);
            if (width.equals(stroke.getAttribute("android:width"))
                    && color.equals(stroke.getAttribute("android:color"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFocusedDrawable(Document doc, String drawable) {
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(i);
            if ("true".equals(item.getAttribute("android:state_focused"))
                    && drawable.equals(item.getAttribute("android:drawable"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStyleItem(Document doc, String styleName, String itemName, String value) {
        NodeList styles = doc.getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            org.w3c.dom.Element style = (org.w3c.dom.Element) styles.item(i);
            if (!styleName.equals(style.getAttribute("name"))) {
                continue;
            }
            NodeList items = style.getElementsByTagName("item");
            for (int j = 0; j < items.getLength(); j++) {
                org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(j);
                if (itemName.equals(item.getAttribute("name"))
                        && value.equals(item.getTextContent())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasViewAttribute(Document doc, String idName, String attrName, String value) {
        NodeList nodes = doc.getElementsByTagName("*");
        String idValue = "@+id/" + idName;
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Element node = (org.w3c.dom.Element) nodes.item(i);
            if (idValue.equals(node.getAttribute("android:id"))
                    && value.equals(node.getAttribute(attrName))) {
                return true;
            }
        }
        return false;
    }
}
