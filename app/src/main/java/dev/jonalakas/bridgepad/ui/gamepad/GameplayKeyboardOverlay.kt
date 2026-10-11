package dev.jonalakas.bridgepad.ui.gamepad

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.KeyboardKey
import dev.jonalakas.bridgepad.input.touch.TouchKeyboardStore
import dev.jonalakas.bridgepad.input.touch.TouchMouseStore

/** In-memory only UI state. Call [reset] whenever the gameplay session ends. */
@Stable
class GameplayKeyboardState {
    var value by mutableStateOf(TextFieldValue())
        private set

    fun update(next: TextFieldValue) {
        TouchMouseStore.notifyRegularInteraction()
        keyboardInputsForChange(value.text, next.text).forEach(TouchKeyboardStore::submit)
        value = next.copy(selection = TextRange(next.text.length))
    }

    fun submit(key: KeyboardKey) {
        TouchMouseStore.notifyRegularInteraction()
        TouchKeyboardStore.submit(KeyboardInput.Key(key))
    }

    /** Send Enter to the computer and clear only the local text mirror. */
    fun sendAndClear() {
        submit(KeyboardKey.ENTER)
        reset()
    }

    fun reset() {
        value = TextFieldValue()
    }
}

/** Shows the same native keyboard over a controller, touchpad, or stream. */
@Composable
internal fun GameplayKeyboardOverlay(
    state: GameplayKeyboardState,
    onClose: () -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var keyboardWasVisible by remember { mutableStateOf(false) }
    val close = {
        keyboardController?.hide()
        onClose()
    }

    LaunchedEffect(imeVisible) {
        if (imeVisible) keyboardWasVisible = true
        else if (keyboardWasVisible) onClose()
    }
    DisposableEffect(activity) {
        val window = activity?.window
        val previousMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            if (previousMode != null) window?.setSoftInputMode(previousMode)
        }
    }
    BackHandler(onBack = close)
    Box(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        GameplayKeyboardInput(
            state = state,
            onClose = close,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GameplayKeyboardInput(
    state: GameplayKeyboardState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var editorValue by remember { mutableStateOf(state.value.withDeleteGuard()) }

    DisposableEffect(Unit) {
        onDispose { keyboardController?.hide() }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    OutlinedTextField(
        value = editorValue,
        onValueChange = { editorValue = state.acceptEditorValue(it) },
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
            .focusRequester(focusRequester),
        label = { Text(stringResource(R.string.keyboard_text_placeholder)) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close_action),
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(
            onSend = {
                state.sendAndClear()
                editorValue = state.value.withDeleteGuard()
            },
        ),
    )
}

private fun GameplayKeyboardState.acceptEditorValue(next: TextFieldValue): TextFieldValue {
    when {
        next.text.startsWith(DELETE_GUARD) -> update(next.withoutDeleteGuard())
        next.text.isEmpty() && value.text.isEmpty() -> submit(KeyboardKey.BACKSPACE)
        else -> update(next)
    }
    return value.withDeleteGuard()
}

private const val DELETE_GUARD = "\u200B"

private fun TextFieldValue.withDeleteGuard(): TextFieldValue = copy(
    text = DELETE_GUARD + text,
    selection = selection.shift(1),
    composition = composition?.shift(1),
)

private fun TextFieldValue.withoutDeleteGuard(): TextFieldValue {
    if (!text.startsWith(DELETE_GUARD)) return this
    return copy(
        text = text.substring(DELETE_GUARD.length),
        selection = selection.shift(-1),
        composition = composition?.shift(-1),
    )
}

private fun TextRange.shift(amount: Int): TextRange = TextRange(
    start = (start + amount).coerceAtLeast(0),
    end = (end + amount).coerceAtLeast(0),
)

internal fun keyboardInputsForChange(previous: String, next: String): List<KeyboardInput> {
    if (previous == next) return emptyList()
    val commonPrefix = previous.indices
        .takeWhile { index -> index < next.length && previous[index] == next[index] }
        .count()
    return buildList {
        val removed = previous.substring(commonPrefix)
        repeat(removed.codePointCount(0, removed.length)) {
            add(KeyboardInput.Key(KeyboardKey.BACKSPACE))
        }
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                add(KeyboardInput.Text(text.toString()))
                text.clear()
            }
        }
        next.substring(commonPrefix).forEach { character ->
            val key = when (character) {
                '\n', '\r' -> KeyboardKey.ENTER
                '\t' -> KeyboardKey.TAB
                else -> null
            }
            if (key == null) {
                text.append(character)
            } else {
                flushText()
                add(KeyboardInput.Key(key))
            }
        }
        flushText()
    }
}
