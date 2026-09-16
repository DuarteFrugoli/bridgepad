package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.mapping.InputMerger
import dev.jonalakas.bridgepad.core.mapping.AdaptiveInputOwnership
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchGamepadSnapshot
import dev.jonalakas.bridgepad.input.touch.TouchGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchMouseStore
import dev.jonalakas.bridgepad.input.usb.DirectUsbGamepadStore
import dev.jonalakas.bridgepad.input.usb.DirectUsbState
import dev.jonalakas.bridgepad.localization.LocalizedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class RoutedInputState(
    val gamepad: VirtualGamepadState = VirtualGamepadState(),
    val inputEventCount: Long = 0,
    val lastInputTimestampNanos: Long? = null,
    val directUsbActive: Boolean = false,
    val captureMessage: LocalizedMessage? = null,
    val captureError: Boolean = false,
    val physicalControllerConnected: Boolean = false,
)

fun interface InputSubscription {
    fun cancel()
}

/**
 * Android composition root for input adapters. Output transports consume only
 * the normalized state exposed here and never depend on a concrete input API.
 */
class InputRouter(scope: CoroutineScope) {
    private val stateLock = Any()
    private val observers = linkedSetOf<(RoutedInputState) -> Unit>()
    private var captureMode = PhysicalCaptureMode.COMPATIBILITY
    private val adaptiveOwnership = AdaptiveInputOwnership()
    private var physical = PhysicalGamepadStore.state.value
    private var touch = TouchGamepadStore.state.value
    private var directUsb = DirectUsbGamepadStore.state.value
    private var lastPhysicalCount = physical.inputEventCount
    private var lastTouchCount = touch.inputEventCount
    private var lastDirectUsbCount = directUsb.inputEventCount
    private var routedEventCount = 0L
    @Volatile
    private var latest = buildState(null)
    val current: RoutedInputState get() = latest

    init {
        scope.launch {
            PhysicalGamepadStore.updates.collect { state ->
                synchronized(stateLock) {
                    val changed = state.inputEventCount != lastPhysicalCount
                    lastPhysicalCount = state.inputEventCount
                    physical = state
                    publishLocked(if (changed && usesCompatibilityInput()) state.lastInputTimestampNanos else null)
                }
            }
        }
        scope.launch {
            TouchGamepadStore.updates.collect { state ->
                synchronized(stateLock) {
                    val changed = state.inputEventCount != lastTouchCount
                    lastTouchCount = state.inputEventCount
                    touch = state
                    publishLocked(if (changed) state.lastInputTimestampNanos else null)
                }
            }
        }
        scope.launch {
            DirectUsbGamepadStore.updates.collect { state ->
                synchronized(stateLock) {
                    val changed = state.inputEventCount != lastDirectUsbCount
                    lastDirectUsbCount = state.inputEventCount
                    directUsb = state
                    publishLocked(if (changed && usesDirectUsbInput()) state.lastInputTimestampNanos else null)
                }
            }
        }
    }

    fun selectAutomatic(captureMode: PhysicalCaptureMode = PhysicalCaptureMode.COMPATIBILITY) {
        synchronized(stateLock) {
            this.captureMode = captureMode
            adaptiveOwnership.clear()
            publishLocked(null)
        }
    }

    fun observe(observer: (RoutedInputState) -> Unit): InputSubscription {
        synchronized(stateLock) {
            observers += observer
            observer(latest)
        }
        return InputSubscription {
            synchronized(stateLock) { observers -= observer }
        }
    }

    fun consumePointer(): PointerReport? = TouchMouseStore.consume()

    fun clearPointer() = TouchMouseStore.clear()

    /** Called with [stateLock] held so inputs from independent adapters remain ordered. */
    private fun publishLocked(eventTimestampNanos: Long?) {
        if (eventTimestampNanos != null) routedEventCount++
        latest = buildState(eventTimestampNanos ?: latest.lastInputTimestampNanos)
        observers.forEach { it(latest) }
    }

    private fun buildState(timestampNanos: Long?): RoutedInputState {
        val directActive = usesDirectUsbInput()
        val physicalSources = if (usesCompatibilityInput()) {
            physical.sourceStates.map { (sourceId, gamepad) -> SourceGamepadState(sourceId, gamepad) }
        } else {
            emptyList()
        }
        val sources = physicalSources +
            SourceGamepadState(
                TouchGamepadStore.sourceId,
                touch.gamepad,
            ) +
            SourceGamepadState(
                DIRECT_USB_SOURCE_ID,
                if (directActive) directUsb.gamepad else VirtualGamepadState(),
            )
        sources.forEach(adaptiveOwnership::observe)
        adaptiveOwnership.retainSources(sources.mapTo(mutableSetOf(), SourceGamepadState::sourceId))
        val ownership = adaptiveOwnership.ownership(sources)
        return RoutedInputState(
            gamepad = InputMerger.merge(sources, ownership),
            inputEventCount = routedEventCount,
            lastInputTimestampNanos = timestampNanos,
            directUsbActive = directUsb.active,
            captureMessage = directUsb.statusMessage,
            captureError = directUsb.statusIsError,
            physicalControllerConnected = physical.devices.isNotEmpty() || directUsb.active,
        )
    }

    private fun usesCompatibilityInput(): Boolean =
        captureMode == PhysicalCaptureMode.COMPATIBILITY

    private fun usesDirectUsbInput(): Boolean =
        captureMode == PhysicalCaptureMode.BACKGROUND_USB &&
            directUsb.active

    private companion object {
        val DIRECT_USB_SOURCE_ID = SourceId("direct-usb")
    }
}
