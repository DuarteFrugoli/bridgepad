package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.compose.runtime.saveable.listSaver

/**
 * Keeps one saved profile while maintaining independent undo/redo branches for
 * portrait and landscape editing.
 */
internal data class TouchscreenLayoutHistory(
    val current: TouchscreenLayoutProfile,
    val landscapeUndoStates: List<TouchscreenLayoutProfile> = emptyList(),
    val landscapeRedoStates: List<TouchscreenLayoutProfile> = emptyList(),
    val portraitUndoStates: List<TouchscreenLayoutProfile> = emptyList(),
    val portraitRedoStates: List<TouchscreenLayoutProfile> = emptyList(),
) {
    fun canUndo(orientation: TouchscreenLayoutOrientation): Boolean =
        undoStates(orientation).isNotEmpty()

    fun canRedo(orientation: TouchscreenLayoutOrientation): Boolean =
        redoStates(orientation).isNotEmpty()

    fun undoCount(orientation: TouchscreenLayoutOrientation): Int =
        undoStates(orientation).size

    /** Records one discrete edit only in the active orientation. */
    fun record(
        orientation: TouchscreenLayoutOrientation,
        next: TouchscreenLayoutProfile,
    ): TouchscreenLayoutHistory {
        val nextLayout = next.layout(orientation)
        if (nextLayout == current.layout(orientation)) return this
        return withOrientationHistory(
            orientation = orientation,
            nextCurrent = current.update(orientation, nextLayout),
            nextUndoStates = undoStates(orientation).appendBounded(current),
            nextRedoStates = emptyList(),
        )
    }

    /** Updates a live drag/resize preview without creating history entries. */
    fun preview(
        orientation: TouchscreenLayoutOrientation,
        next: TouchscreenLayoutProfile,
    ): TouchscreenLayoutHistory {
        val nextLayout = next.layout(orientation)
        return if (nextLayout == current.layout(orientation)) {
            this
        } else {
            copy(current = current.update(orientation, nextLayout))
        }
    }

    /** Commits every live update since [before] as one orientation-local gesture. */
    fun commitGesture(
        orientation: TouchscreenLayoutOrientation,
        before: TouchscreenLayoutProfile,
    ): TouchscreenLayoutHistory =
        if (before.layout(orientation) == current.layout(orientation)) {
            this
        } else {
            withOrientationHistory(
                orientation = orientation,
                nextCurrent = current,
                nextUndoStates = undoStates(orientation).appendBounded(before),
                nextRedoStates = emptyList(),
            )
        }

    fun undo(orientation: TouchscreenLayoutOrientation): TouchscreenLayoutHistory {
        val undo = undoStates(orientation)
        val previous = undo.lastOrNull() ?: return this
        return withOrientationHistory(
            orientation = orientation,
            nextCurrent = current.update(orientation, previous.layout(orientation)),
            nextUndoStates = undo.dropLast(1),
            nextRedoStates = redoStates(orientation).appendBounded(current),
        )
    }

    fun redo(orientation: TouchscreenLayoutOrientation): TouchscreenLayoutHistory {
        val redo = redoStates(orientation)
        val next = redo.lastOrNull() ?: return this
        return withOrientationHistory(
            orientation = orientation,
            nextCurrent = current.update(orientation, next.layout(orientation)),
            nextUndoStates = undoStates(orientation).appendBounded(current),
            nextRedoStates = redo.dropLast(1),
        )
    }

    private fun undoStates(
        orientation: TouchscreenLayoutOrientation,
    ): List<TouchscreenLayoutProfile> = when (orientation) {
        TouchscreenLayoutOrientation.LANDSCAPE -> landscapeUndoStates
        TouchscreenLayoutOrientation.PORTRAIT -> portraitUndoStates
    }

    private fun redoStates(
        orientation: TouchscreenLayoutOrientation,
    ): List<TouchscreenLayoutProfile> = when (orientation) {
        TouchscreenLayoutOrientation.LANDSCAPE -> landscapeRedoStates
        TouchscreenLayoutOrientation.PORTRAIT -> portraitRedoStates
    }

    private fun withOrientationHistory(
        orientation: TouchscreenLayoutOrientation,
        nextCurrent: TouchscreenLayoutProfile,
        nextUndoStates: List<TouchscreenLayoutProfile>,
        nextRedoStates: List<TouchscreenLayoutProfile>,
    ): TouchscreenLayoutHistory = when (orientation) {
        TouchscreenLayoutOrientation.LANDSCAPE -> copy(
            current = nextCurrent,
            landscapeUndoStates = nextUndoStates,
            landscapeRedoStates = nextRedoStates,
        )
        TouchscreenLayoutOrientation.PORTRAIT -> copy(
            current = nextCurrent,
            portraitUndoStates = nextUndoStates,
            portraitRedoStates = nextRedoStates,
        )
    }
}

internal val TouchscreenLayoutHistorySaver =
    listSaver<TouchscreenLayoutHistory, Any>(
        save = { history ->
            listOf(
                TouchscreenLayoutProfileCodec.encode(history.current),
                history.landscapeUndoStates.encodeProfiles(),
                history.landscapeRedoStates.encodeProfiles(),
                history.portraitUndoStates.encodeProfiles(),
                history.portraitRedoStates.encodeProfiles(),
            )
        },
        restore = { saved ->
            val current = TouchscreenLayoutProfileCodec.decode(saved[0] as? String)
                ?: return@listSaver null
            TouchscreenLayoutHistory(
                current = current,
                landscapeUndoStates = saved.decodeProfilesAt(1),
                landscapeRedoStates = saved.decodeProfilesAt(2),
                portraitUndoStates = saved.decodeProfilesAt(3),
                portraitRedoStates = saved.decodeProfilesAt(4),
            )
        },
    )

private fun List<TouchscreenLayoutProfile>.appendBounded(
    profile: TouchscreenLayoutProfile,
): List<TouchscreenLayoutProfile> = (this + profile).takeLast(LAYOUT_HISTORY_LIMIT)

private fun List<TouchscreenLayoutProfile>.encodeProfiles(): ArrayList<String> =
    ArrayList(map(TouchscreenLayoutProfileCodec::encode))

private fun List<Any>.decodeProfilesAt(index: Int): List<TouchscreenLayoutProfile> =
    (getOrNull(index) as? List<*>)
        .orEmpty()
        .mapNotNull { TouchscreenLayoutProfileCodec.decode(it as? String) }
        .takeLast(LAYOUT_HISTORY_LIMIT)

internal const val LAYOUT_HISTORY_LIMIT = 40
