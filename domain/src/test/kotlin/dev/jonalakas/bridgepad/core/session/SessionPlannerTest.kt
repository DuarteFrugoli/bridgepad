package dev.jonalakas.bridgepad.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlannerTest {
    private val pcBluetooth = OutputAdapterDescriptor(
        id = OutputAdapterIds.GENERIC_BLUETOOTH_HID,
        connectionMethod = ConnectionMethod.BLUETOOTH,
        supportedDestinations = setOf(DestinationType.PC),
        targetSelectionMode = TargetSelectionMode.PAIRED_OR_NEW,
    )
    private val catalog = OutputAdapterCatalog(listOf(pcBluetooth))

    @Test
    fun changingConnectionClearsItsTargetButPreservesInputPreferences() {
        val complete = SessionDraft(
            destinationType = DestinationType.PC,
            connectionMethod = ConnectionMethod.BLUETOOTH,
            outputAdapterId = OutputAdapterIds.GENERIC_BLUETOOTH_HID,
            destinationTarget = DestinationTarget(DestinationTargetKind.EXISTING, "pc"),
            physicalCaptureMode = PhysicalCaptureMode.BACKGROUND_USB,
        )

        val changedConnection = complete.selectConnection(ConnectionMethod.WIFI)

        assertEquals(DestinationType.PC, changedConnection.destinationType)
        assertEquals(ConnectionMethod.WIFI, changedConnection.connectionMethod)
        assertEquals(null, changedConnection.outputAdapterId)
        assertEquals(null, changedConnection.destinationTarget)
        assertEquals(PhysicalCaptureMode.BACKGROUND_USB, changedConnection.physicalCaptureMode)
    }

    @Test
    fun soleCompatibleAdapterIsResolvedWithoutExposingItToTheUser() {
        val result = SessionPlanner.plan(
            draft = SessionDraft(
                destinationType = DestinationType.PC,
                connectionMethod = ConnectionMethod.BLUETOOTH,
                destinationTarget = DestinationTarget(DestinationTargetKind.EXISTING, "pc"),
            ),
            adapters = catalog,
            connectionAvailable = true,
            availableTargetIds = listOf("pc"),
        )

        assertTrue(result is SessionPlanResult.Ready)
        assertEquals(
            OutputAdapterIds.GENERIC_BLUETOOTH_HID,
            (result as SessionPlanResult.Ready).configuration.outputAdapterId,
        )
    }

    @Test
    fun unsupportedConnectionDoesNotFallBackToBluetoothHid() {
        val result = SessionPlanner.plan(
            draft = SessionDraft(
                destinationType = DestinationType.PC,
                connectionMethod = ConnectionMethod.WIFI,
                destinationTarget = DestinationTarget(DestinationTargetKind.EXISTING, "pc"),
            ),
            adapters = catalog,
            connectionAvailable = true,
            availableTargetIds = emptyList(),
        )

        assertEquals(
            SessionPlanResult.Incomplete(SessionPlanProblem.ADAPTER_UNAVAILABLE),
            result,
        )
    }

    @Test
    fun explicitDesktopBluetoothAdapterWinsWithoutActivatingHid() {
        val desktopBluetooth = pcBluetooth.copy(id = OutputAdapterIds.DESKTOP_BLUETOOTH)
        val result = SessionPlanner.plan(
            draft = SessionDraft(
                destinationType = DestinationType.PC,
                connectionMethod = ConnectionMethod.BLUETOOTH,
                outputAdapterId = OutputAdapterIds.DESKTOP_BLUETOOTH,
                destinationTarget = DestinationTarget(DestinationTargetKind.EXISTING, "pc"),
            ),
            adapters = OutputAdapterCatalog(listOf(pcBluetooth, desktopBluetooth)),
            connectionAvailable = true,
            availableTargetIds = listOf("pc"),
        )

        assertTrue(result is SessionPlanResult.Ready)
        assertEquals(
            OutputAdapterIds.DESKTOP_BLUETOOTH,
            (result as SessionPlanResult.Ready).configuration.outputAdapterId,
        )
    }
}
