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
    private val observers = linkedSetOf<RouteObserver>()
    private val compatibilityOwnership = AdaptiveInputOwnership()
    private val directUsbOwnership = AdaptiveInputOwnership()
    private var physical = PhysicalGamepadStore.state.value
    private var touch = TouchGamepadStore.state.value
    private var directUsb = DirectUsbGamepadStore.state.value
    private var lastPhysicalCount = physical.inputEventCount
    private var lastTouchCount = touch.inputEventCount
    private var lastDirectUsbCount = directUsb.inputEventCount
    private var compatibilityEventCount = 0L
    private var directUsbEventCount = 0L
    private var compatibilityTimestampNanos: Long? = null
    private var directUsbTimestampNanos: Long? = null

    init {
        scope.launch {
            PhysicalGamepadStore.updates.collect { state ->
                synchronized(stateLock) {
                    val changed = state.inputEventCount != lastPhysicalCount
                    lastPhysicalCount = state.inputEventCount
                    physical = state
                    if (changed) {
                        compatibilityEventCount++
                        compatibilityTimestampNanos = state.lastInputTimestampNanos
                    }
                    publishLocked()
                }
            }
        }
        scope.launch {
            TouchGamepadStore.updates.collect { state ->
                synchronized(stateLock) {
                    val changed = state.inputEventCount != lastTouchCount
                    lastTouchCount = state.inputEventCount
                    touch = state
                    if (changed) {
                        compatibilityEventCount++
                        directUsbEventCount++
                        compatibilityTimestampNanos = state.lastInputTimestampNanos
                        directUsbTimestampNanos = state.lastInputTimestampNanos
                    }
                    publishLocked()
                }
            }
        }
        scope.launch {
            DirectUsbGamepadStore.updates.collect { state ->
                synchronized(stateLock) {
                    val changed = state.inputEventCount != lastDirectUsbCount
                    lastDirectUsbCount = state.inputEventCount
                    directUsb = state
                    if (changed) {
                        directUsbEventCount++
                        directUsbTimestampNanos = state.lastInputTimestampNanos
                    }
                    publishLocked()
                }
            }
        }
    }

    fun current(captureMode: PhysicalCaptureMode): RoutedInputState = synchronized(stateLock) {
        buildState(captureMode)
    }

    fun observe(
        captureMode: PhysicalCaptureMode,
        observer: (RoutedInputState) -> Unit,
    ): InputSubscription {
        val routeObserver = RouteObserver(captureMode, observer)
        synchronized(stateLock) {
            observers += routeObserver
            observer(buildState(captureMode))
        }
        return InputSubscription {
            synchronized(stateLock) { observers -= routeObserver }
        }
    }

    fun consumePointer(): PointerReport? = TouchMouseStore.consume()

    fun clearPointer() = TouchMouseStore.clear()

    /** Called with [stateLock] held so inputs from independent adapters remain ordered. */
    private fun publishLocked() {
        observers.forEach { route -> route.observer(buildState(route.captureMode)) }
    }

    private fun buildState(captureMode: PhysicalCaptureMode): RoutedInputState {
        val directActive = captureMode == PhysicalCaptureMode.BACKGROUND_USB && directUsb.active
        val physicalSources = if (captureMode == PhysicalCaptureMode.COMPATIBILITY) {
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
        val adaptiveOwnership = if (captureMode == PhysicalCaptureMode.BACKGROUND_USB) {
            directUsbOwnership
        } else {
            compatibilityOwnership
        }
        sources.forEach(adaptiveOwnership::observe)
        adaptiveOwnership.retainSources(sources.mapTo(mutableSetOf(), SourceGamepadState::sourceId))
        val ownership = adaptiveOwnership.ownership(sources)
        return RoutedInputState(
            gamepad = InputMerger.merge(sources, ownership),
            inputEventCount = if (captureMode == PhysicalCaptureMode.BACKGROUND_USB) {
                directUsbEventCount
            } else {
                compatibilityEventCount
            },
            lastInputTimestampNanos = if (captureMode == PhysicalCaptureMode.BACKGROUND_USB) {
                directUsbTimestampNanos
            } else {
                compatibilityTimestampNanos
            },
            directUsbActive = directUsb.active,
            captureMessage = directUsb.statusMessage,
            captureError = directUsb.statusIsError,
            physicalControllerConnected = physical.devices.isNotEmpty() || directUsb.active,
        )
    }

    private data class RouteObserver(
        val captureMode: PhysicalCaptureMode,
        val observer: (RoutedInputState) -> Unit,
    )

    private companion object {
        val DIRECT_USB_SOURCE_ID = SourceId("direct-usb")
    }
}
