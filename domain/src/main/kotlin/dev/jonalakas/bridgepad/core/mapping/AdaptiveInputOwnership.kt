package dev.jonalakas.bridgepad.core.mapping

import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import kotlin.math.abs

/** Tracks the last intentional non-neutral source independently for each axis. */
class AdaptiveInputOwnership {
    private var activitySequence = 0L
    private val previousStates = mutableMapOf<SourceId, VirtualGamepadState>()
    private val axisActivity = VirtualAxis.entries.associateWith {
        mutableMapOf<SourceId, Long>()
    }
    private val dpadActivity = mutableMapOf<SourceId, Long>()

    fun observe(source: SourceGamepadState) {
        val previous = previousStates[source.sourceId] ?: VirtualGamepadState()
        VirtualAxis.entries.forEach { axis ->
            val previousValue = previous.valueOf(axis)
            val nextValue = source.gamepad.valueOf(axis)
            if (abs(nextValue - previousValue) > CHANGE_EPSILON && !axis.isNeutral(nextValue)) {
                axisActivity.getValue(axis)[source.sourceId] = ++activitySequence
            }
        }
        if (source.gamepad.dpad != previous.dpad && source.gamepad.dpad != DpadDirection.NEUTRAL) {
            dpadActivity[source.sourceId] = ++activitySequence
        }
        previousStates[source.sourceId] = source.gamepad
    }

    fun remove(sourceId: SourceId) {
        previousStates.remove(sourceId)
        axisActivity.values.forEach { it.remove(sourceId) }
        dpadActivity.remove(sourceId)
    }

    fun ownership(sources: Collection<SourceGamepadState>): InputOwnership {
        val activeById = sources.associateBy(SourceGamepadState::sourceId)
        val axes = VirtualAxis.entries.mapNotNull { axis ->
            axisActivity.getValue(axis)
                .asSequence()
                .filter { (sourceId) ->
                    activeById[sourceId]?.gamepad?.valueOf(axis)?.let { !axis.isNeutral(it) } == true
                }
                .maxByOrNull { it.value }
                ?.key
                ?.let { axis to it }
        }.toMap()
        val dpad = dpadActivity
            .asSequence()
            .filter { (sourceId) ->
                activeById[sourceId]?.gamepad?.dpad != DpadDirection.NEUTRAL
            }
            .maxByOrNull { it.value }
            ?.key
        return InputOwnership(axes = axes, dpad = dpad)
    }

    fun retainSources(sourceIds: Set<SourceId>) {
        previousStates.keys.filterNot(sourceIds::contains).forEach(::remove)
    }

    fun clear() {
        previousStates.clear()
        axisActivity.values.forEach { it.clear() }
        dpadActivity.clear()
    }

    private fun VirtualAxis.isNeutral(value: Float): Boolean = when (this) {
        VirtualAxis.LEFT_TRIGGER, VirtualAxis.RIGHT_TRIGGER -> value <= NEUTRAL_EPSILON
        else -> abs(value) <= NEUTRAL_EPSILON
    }

    private companion object {
        const val CHANGE_EPSILON = 0.001f
        const val NEUTRAL_EPSILON = 0.01f
    }
}
