package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaEncoderLaunchContractTest {

    private fun optimization(
        requested: String = "vulkan",
        resolved: String = requested,
        locked: Boolean = true,
        fallbackAllowed: Boolean = requested == "auto",
    ) = JSONObject()
        .put(
            "resolved_profile",
            JSONObject().put(
                "fields",
                JSONObject().put(
                    "encoder_backend",
                    JSONObject()
                        .put("value", resolved)
                        .put("locked", locked),
                ),
            ),
        )
        .put(
            "encoder_resolution",
            JSONObject()
                .put("requested", requested)
                .put("resolved", resolved)
                .put("locked", locked)
                .put("fallback_allowed", fallbackAllowed),
        )

    @Test
    fun blankHostDefaultNeedsNoEncoderAssertion() {
        assertTrue(NovaEncoderLaunchContract.honors(JSONObject(), ""))
    }

    @Test
    fun exactBackendRequiresMatchingLockedTypedProvenance() {
        assertTrue(NovaEncoderLaunchContract.honors(optimization(), "VULKAN"))
        assertFalse(NovaEncoderLaunchContract.honors(optimization(resolved = "vaapi"), "vulkan"))
        assertFalse(NovaEncoderLaunchContract.honors(optimization(locked = false), "vulkan"))
        assertFalse(NovaEncoderLaunchContract.honors(JSONObject(), "vulkan"))
    }

    @Test
    fun autoRequiresTheHostToDeclareFallbackWhileExactBackendsForbidIt() {
        assertTrue(NovaEncoderLaunchContract.honors(optimization(requested = "auto"), "auto"))
        assertFalse(
            NovaEncoderLaunchContract.honors(
                optimization(requested = "auto", fallbackAllowed = false),
                "auto",
            ),
        )
        assertFalse(
            NovaEncoderLaunchContract.honors(
                optimization(requested = "vulkan", fallbackAllowed = true),
                "vulkan",
            ),
        )
    }
}
