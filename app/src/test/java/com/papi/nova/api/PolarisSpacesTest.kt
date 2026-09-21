package com.papi.nova.api
import org.junit.Assert.*
import org.junit.Test

@org.robolectric.annotation.Config(sdk = [33])
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class PolarisSpacesTest {
    private val valid = """{"schema":1,"status":true,"enabled":true,"available":true,"can_switch":true,"selected_space_id":"a","spaces":[{"id":"a","name":"Alex","state":"ready","selected":true},{"id":"b","name":"Sam","state":"in_use","selected":false}]}"""
    @Test fun parsesPermissionScopedChoicesAndActivity() {
        val result = requireNotNull(PolarisSpaces.parse(valid))
        assertEquals("Alex", result.selected?.name); assertEquals("in_use", result.spaces[1].state)
        assertTrue(result.canSwitch)
    }
    @Test fun rejectsAmbiguousIdentityTypesAndState() {
        for (bad in listOf(valid.replace("\"id\":\"b\"", "\"id\":\"a\""),
            valid.replace("\"selected_space_id\":\"a\"", "\"selected_space_id\":\"b\""),
            valid.replace("\"selected\":false", "\"selected\":true"),
            valid.replace("\"state\":\"in_use\"", "\"state\":\"idle_maybe\""),
            valid.replace("\"can_switch\":true", "\"can_switch\":\"true\""),
            valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
            valid.replace("\"available\":true", "\"available\":false"))) assertNull(bad, PolarisSpaces.parse(bad))
    }
    @Test fun unavailableHostHasNoImplicitSelection() {
        val result = PolarisSpaces.parse("""{"schema":1,"status":true,"enabled":false,"available":false,"can_switch":false,"selected_space_id":"","spaces":[]}""")
        assertNotNull(result); assertNull(result?.selected)
    }
    @Test fun desktopChoiceRequiresAnExplicitBooleanGrant() {
        val desktop = valid.replace("\"selected_space_id\":\"a\"", "\"selected_space_id\":\"desktop\"")
            .replace("\"selected\":true", "\"selected\":false")
        assertNull(PolarisSpaces.parse(desktop))
        val allowed = desktop.replace("\"schema\":1", "\"schema\":1,\"desktop_allowed\":true")
        val parsed = requireNotNull(PolarisSpaces.parse(allowed))
        assertEquals("desktop", parsed.selectedId); assertNull(parsed.selected)
        assertNull(PolarisSpaces.parse(allowed.replace("\"desktop_allowed\":true", "\"desktop_allowed\":\"true\"")))
        assertNull(PolarisSpaces.parse(valid.replace("\"id\":\"a\"", "\"library_enabled\":\"true\",\"id\":\"a\"")))
    }

    @Test fun readsTheReasonFieldsWhenTheHostSendsThem() {
        val body = """{"schema":1,"status":true,"enabled":true,"available":false,"can_switch":false,"selected_space_id":"","unavailable_reason":"controller_missing","switch_blocked_reason":"unavailable","default_space_id":"a","capacity":{"concurrent_limit":1,"concurrent_active":1},"spaces":[{"id":"a","name":"Alex","state":"unavailable","selected":false,"can_open":false,"blocked_reason":"unavailable"}]}"""
        val parsed = requireNotNull(PolarisSpaces.parse(body))
        assertEquals("controller_missing", parsed.unavailableReason); assertEquals("unavailable", parsed.switchBlockedReason)
        assertEquals("a", parsed.defaultSpaceId); assertEquals(PolarisSpacesCapacity(1, 1), parsed.capacity)
        assertFalse(parsed.spaces[0].canOpen); assertEquals("unavailable", parsed.spaces[0].blockedReason)
        assertFalse(parsed.desktopSelected)
    }
    @Test fun readsTheLauncherASpaceOpensWhenTheHostSaysSo() {
        val body = """{"schema":1,"status":true,"enabled":true,"available":true,"can_switch":true,"selected_space_id":"a","spaces":[{"id":"a","name":"Alex","state":"ready","selected":true,"launcher":"heroic"},{"id":"b","name":"Sam","state":"ready","selected":false}]}"""
        val parsed = requireNotNull(PolarisSpaces.parse(body))
        assertEquals("heroic", parsed.spaces[0].launcher)
        assertEquals("an older host says nothing, and that is not an error", null, parsed.spaces[1].launcher)
        assertEquals(
            "a launcher this build has never heard of must not cost the whole list",
            "brand_new", requireNotNull(PolarisSpaces.parse(body.replace("heroic", "brand_new"))).spaces[0].launcher,
        )
        for (unusable in listOf("7", "\"two words\"", "null", "[\"heroic\"]")) {
            val parsed = requireNotNull(PolarisSpaces.parse(body.replace("\"heroic\"", unusable))) { "a label cost the whole list: $unusable" }
            assertEquals(null, parsed.spaces[0].launcher)
            assertEquals("Alex", parsed.spaces[0].name)
        }
    }
    @Test fun derivesCanOpenForHostsThatDoNotSayAndRejectsTheWrongTypes() {
        val parsed = requireNotNull(PolarisSpaces.parse(valid))
        assertTrue(parsed.spaces[0].canOpen); assertFalse(parsed.spaces[1].canOpen)
        assertNull(parsed.unavailableReason); assertNull(parsed.capacity); assertNull(parsed.defaultSpaceId)
        val atCapacity = valid.replace("\"state\":\"ready\"", "\"state\":\"ready\",\"can_open\":false,\"blocked_reason\":\"at_capacity\"")
        val full = requireNotNull(PolarisSpaces.parse(atCapacity))
        assertFalse(full.spaces[0].canOpen); assertFalse(full.spaces[0].openable); assertEquals("at_capacity", full.spaces[0].blockedReason)
        val running = valid.replace("\"state\":\"ready\"", "\"state\":\"running\",\"can_open\":false,\"blocked_reason\":\"running\"")
        assertTrue("a Space already running for this device can be resumed", requireNotNull(PolarisSpaces.parse(running)).spaces[0].openable)
        for (bad in listOf(
            valid.replace("\"schema\":1", "\"schema\":1,\"capacity\":{\"concurrent_limit\":\"1\",\"concurrent_active\":0}"),
            valid.replace("\"schema\":1", "\"schema\":1,\"unavailable_reason\":7"),
            valid.replace("\"state\":\"ready\"", "\"state\":\"ready\",\"can_open\":\"yes\""),
            valid.replace("\"schema\":1", "\"schema\":1,\"default_space_id\":\"has space\""),
        )) assertNull(bad, PolarisSpaces.parse(bad))
        val blank = valid.replace("\"schema\":1", "\"schema\":1,\"unavailable_reason\":\"\",\"switch_blocked_reason\":null,\"capacity\":null")
        val parsedBlank = requireNotNull(PolarisSpaces.parse(blank))
        assertNull(parsedBlank.unavailableReason); assertNull(parsedBlank.switchBlockedReason); assertNull(parsedBlank.capacity)
        val novel = valid.replace("\"schema\":1", "\"schema\":1,\"switch_blocked_reason\":\"brand_new_reason\"")
        assertEquals("a reason the client does not know is kept, never fatal", "brand_new_reason", requireNotNull(PolarisSpaces.parse(novel)).switchBlockedReason)
    }
}
