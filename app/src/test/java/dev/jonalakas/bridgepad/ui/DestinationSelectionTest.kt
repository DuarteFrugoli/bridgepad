package dev.jonalakas.bridgepad.ui

import dev.jonalakas.bridgepad.ui.home.DestinationSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationSelectionTest {
    @Test
    fun bluetoothOffRequiresSelectionAgainEvenWithSavedHost() {
        assertEquals(DestinationSelection.CHOOSE_PC, DestinationSelection.requestFor("00:11:22:33:44:55", false, false))
    }

    @Test
    fun bluetoothOffDiscardsStaleNewPairingChoice() {
        assertEquals(DestinationSelection.CHOOSE_PC, DestinationSelection.requestFor(null, true, false))
    }

    @Test
    fun absentSavedHostRequiresSelectionInsteadOfDiscoverability() {
        assertEquals(DestinationSelection.CHOOSE_PC, DestinationSelection.requestFor(null, false))
    }

    @Test
    fun pairedHostConnectsWithoutDiscoverability() {
        assertEquals("00:11:22:33:44:55", DestinationSelection.requestFor("00:11:22:33:44:55", false))
    }

    @Test
    fun discoverabilityRequiresExplicitNewPairingSelection() {
        assertEquals(DestinationSelection.NEW_PC, DestinationSelection.requestFor(null, true))
    }

    @Test
    fun selectedHostTakesPrecedenceOverStaleNewPairingChoice() {
        assertEquals("00:11:22:33:44:55", DestinationSelection.requestFor("00:11:22:33:44:55", true))
    }
}
