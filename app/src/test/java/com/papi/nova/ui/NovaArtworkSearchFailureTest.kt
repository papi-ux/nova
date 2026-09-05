package com.papi.nova.ui

import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovaArtworkSearchFailureTest {

    @Test
    fun codedHostFailuresGetTheirOwnMessage() {
        assertEquals(R.string.nova_artwork_search_no_key, NovaArtworkSearchFailure.messageRes("steamgriddb_key_missing"))
        assertEquals(R.string.nova_artwork_search_key_rejected, NovaArtworkSearchFailure.messageRes("steamgriddb_unauthorized"))
        assertEquals(R.string.nova_artwork_search_rate_limited, NovaArtworkSearchFailure.messageRes(" STEAMGRIDDB_RATE_LIMITED "))
    }

    @Test
    fun unknownOrMissingCodesKeepTheGenericMessage() {
        assertEquals(R.string.nova_artwork_search_failed, NovaArtworkSearchFailure.messageRes(null))
        assertEquals(R.string.nova_artwork_search_failed, NovaArtworkSearchFailure.messageRes(""))
        assertEquals(R.string.nova_artwork_search_failed, NovaArtworkSearchFailure.messageRes("steamgriddb_unavailable"))
        assertEquals(R.string.nova_artwork_search_failed, NovaArtworkSearchFailure.messageRes("something-else"))
    }

    @Test
    fun codesAreNormalizedAndBlankMeansNone() {
        assertEquals("steamgriddb_unauthorized", NovaArtworkSearchFailure.normalizeCode(" STEAMGRIDDB_Unauthorized "))
        assertNull(NovaArtworkSearchFailure.normalizeCode("   "))
        assertNull(NovaArtworkSearchFailure.normalizeCode(""))
        assertNull(NovaArtworkSearchFailure.normalizeCode(null))
        assertNull(NovaArtworkSearchFailure.codeFrom(null))
    }
}
