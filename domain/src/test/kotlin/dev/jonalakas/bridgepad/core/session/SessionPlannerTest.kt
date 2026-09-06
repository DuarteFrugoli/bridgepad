package dev.jonalakas.bridgepad.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlannerTest {
    private val pcBluetooth = OutputAdapterDescriptor(
        id = OutputAdapterIds.GENERIC_BLUETOOTH_HID,
        connectionMethod = ConnectionMethod.BLUETOOTH,
        supportedDestinations = setOf(DestinationType.WINDOWS, DestinationType.LINUX),
        targetSelectionMode = TargetSelectionMode.PAIRED_OR_NEW,
    )
    private val catalog = OutputAdapterCatalog(listOf(pcBluetooth))

    @Test
    fun selectionsClearEveryDependentStep() {
        val complete = SessionDraft(
            destinationType = DestinationType.WINDOWS,
            connectionMethod = ConnectionMethod.BLUETOOTH,
            outputAdapterId = OutputAdapterIds.GENERIC_BLUETOOTH_HID,
            destinationTarget = DestinationTarget(DestinationTargetKind.EXISTING, "pc"),
            inputMode = InputMode.PHYSICAL_GAMEPAD,
            physicalCaptureMode = PhysicalCaptureMode.BACKGROUND_USB,
        )

        val changedDestination = complete.selectDestination(DestinationType.PLAYSTATION)

        assertEquals(DestinationType.PLAYSTATION, changedDestination.destinationType)
        assertEquals(null, changedDestination.connectionMethod)
        assertEquals(null, changedDestination.destinationTarget)
        assertEquals(null, changedDestination.inputMode)
    }

    @Test
    fun soleCompatibleAdapterIsResolvedWithoutExposingItToTheUser() {
        val result = SessionPlanner.plan(
            draft = SessionDraft(
                destinationType = DestinationType.WINDOWS,
                connectionMethod = ConnectionMethod.BLUETOOTH,
                destinationTarget = DestinationTarget(DestinationTargetKind.EXISTING, "pc"),
                inputMode = InputMode.TOUCHSCREEN,
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
    fun unsupportedConsoleDoesNotFallBackToGenericPcHid() {
        val result = SessionPlanner.plan(
            draft = SessionDraft(
                destinationType = DestinationType.PLAYSTATION,
                connectionMethod = ConnectionMethod.BLUETOOTH,
                destinationTarget = DestinationTarget(DestinationTargetKind.NEW_PAIRING),
                inputMode = InputMode.TOUCHSCREEN,
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
}
