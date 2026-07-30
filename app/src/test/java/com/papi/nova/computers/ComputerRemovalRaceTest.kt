package com.papi.nova.computers

import com.papi.nova.nvstream.http.ComputerDetails
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerRemovalRaceTest {
    @Test
    fun removedOrReplacedTupleCannotPublishFromStaleComputerObject() {
        val uuid = "removed-server"
        val staleDetails = ComputerDetails().apply { this.uuid = uuid }
        val staleTuple = PollingTuple(staleDetails)
        val tuples = mutableMapOf(uuid to staleTuple)

        assertTrue(isCurrentPollingComputer(tuples, staleDetails))

        tuples.remove(uuid)
        assertFalse(isCurrentPollingComputer(tuples, staleDetails))

        val replacementDetails = ComputerDetails().apply { this.uuid = uuid }
        tuples[uuid] = PollingTuple(replacementDetails)

        assertFalse(isCurrentPollingComputer(tuples, staleDetails))
        assertTrue(isCurrentPollingComputer(tuples, replacementDetails))
    }
}
