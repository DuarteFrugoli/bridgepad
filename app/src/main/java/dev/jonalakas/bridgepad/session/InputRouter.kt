package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.mapping.InputMerger
import dev.jonalakas.bridgepad.core.mapping.InputOwnership
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.session.InputMode
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class RoutedInputState(
    val gamepad: VirtualGamepadState = VirtualGamepadState(),
    val inputEventCount: Long = 0,
    val lastInputTimestampNanos: Long? = null,
    val directUsbActive: Boolean = false,
    val captureMessage: LocalizedMessage? = null,
    val captureError: Boolean = false,
)

/**
 * Android composition root for input adapters. Output transports consume only
 * the normalized state exposed here and never depend on a concrete input API.
 */
class InputRouter(scope: CoroutineScope) {
    private var inputMode = InputMode.TOUCHSCREEN
    private var captureMode = PhysicalCaptureMode.COMPATIBILITY
    private var physical = PhysicalGamepadStore.state.value
    private var touch = TouchGamepadStore.state.value
    private var directUsb = DirectUsbGamepadStore.state.value
    private var lastPhysicalCount = physical.inputEventCount
    private var lastTouchCount = touch.inputEventCount
    private var lastDirectUsbCount = directUsb.inputEventCount
    private var routedEventCount = 0L
    private var latest = buildState(null)
    private val mutableUpdates = MutableSharedFlow<RoutedInputState>(
        replay = 1,
        extraBufferCapacity = 64,
    ).also { it.tryEmit(latest) }

    val updates: SharedFlow<RoutedInputState> = mutableUpdates.asSharedFlow()
    val current: RoutedInputState get() = latest

    init {
        scope.launch {
            PhysicalGamepadStore.updates.collect { state ->
                val changed = state.inputEventCount != lastPhysicalCount
                lastPhysicalCount = state.inputEventCount
                physical = state
                publish(if (changed && usesCompatibilityInput()) state.lastInputTimestampNanos else null)
            }
        }
        scope.launch {
            TouchGamepadStore.updates.collect { state ->
                val changed = state.inputEventCount != lastTouchCount
                lastTouchCount = state.inputEventCount
                touch = state
                publish(if (changed && inputMode == InputMode.TOUCHSCREEN) state.lastInputTimestampNanos else null)
            }
        }
        scope.launch {
            DirectUsbGamepadStore.state.collect { state ->
                val changed = state.inputEventCount != lastDirectUsbCount
                lastDirectUsbCount = state.inputEventCount
                directUsb = state
                publish(if (changed && usesDirectUsbInput()) state.lastInputTimestampNanos else null)
            }
        }
    }

    fun select(inputMode: InputMode, captureMode: PhysicalCaptureMode?) {
        this.inputMode = inputMode
        this.captureMode = captureMode ?: PhysicalCaptureMode.COMPATIBILITY
        publish(null)
    }

    fun consumePointer(): PointerReport? = TouchMouseStore.consume()

    fun clearPointer() = TouchMouseStore.clear()

    private fun publish(eventTimestampNanos: Long?) {
        if (eventTimestampNanos != null) routedEventCount++
        latest = buildState(eventTimestampNanos ?: latest.lastInputTimestampNanos)
        mutableUpdates.tryEmit(latest)
    }

    private fun buildState(timestampNanos: Long?): RoutedInputState {
        val directActive = usesDirectUsbInput()
        val primary = when {
            inputMode == InputMode.TOUCHSCREEN -> TouchGamepadStore.sourceId
            directActive -> DIRECT_USB_SOURCE_ID
            else -> physical.devices.firstOrNull()?.sourceId
        }
        val ownership = primary?.let { primarySource ->
            InputOwnership(
                axes = VirtualAxis.entries.associateWith { primarySource },
                dpad = primarySource,
            )
        } ?: InputOwnership()
        val physicalSources = if (usesCompatibilityInput()) {
            physical.sourceStates.map { (sourceId, gamepad) -> SourceGamepadState(sourceId, gamepad) }
        } else {
            emptyList()
        }
        val sources = physicalSources +
            SourceGamepadState(
                TouchGamepadStore.sourceId,
                if (inputMode == InputMode.TOUCHSCREEN) touch.gamepad else VirtualGamepadState(),
            ) +
            SourceGamepadState(
                DIRECT_USB_SOURCE_ID,
                if (directActive) directUsb.gamepad else VirtualGamepadState(),
            )
        return RoutedInputState(
            gamepad = InputMerger.merge(sources, ownership),
            inputEventCount = routedEventCount,
            lastInputTimestampNanos = timestampNanos,
            directUsbActive = directUsb.active,
            captureMessage = directUsb.statusMessage,
            captureError = directUsb.statusIsError,
        )
    }

    private fun usesCompatibilityInput(): Boolean =
        inputMode == InputMode.PHYSICAL_GAMEPAD && captureMode == PhysicalCaptureMode.COMPATIBILITY

    private fun usesDirectUsbInput(): Boolean =
        inputMode == InputMode.PHYSICAL_GAMEPAD &&
            captureMode == PhysicalCaptureMode.BACKGROUND_USB &&
            directUsb.active

    private companion object {
        val DIRECT_USB_SOURCE_ID = SourceId("direct-usb")
    }
}
