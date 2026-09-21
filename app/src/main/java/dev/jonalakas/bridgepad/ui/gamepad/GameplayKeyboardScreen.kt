package dev.jonalakas.bridgepad.ui.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** In-memory only UI state. Call [reset] whenever the gameplay session ends. */
@Stable
class GameplayKeyboardState {
    var value by mutableStateOf(TextFieldValue())
        private set

    fun update(next: TextFieldValue) {
        keyboardInputsForChange(value.text, next.text).forEach(TouchKeyboardStore::submit)
        value = next.copy(selection = TextRange(next.text.length))
    }

    fun clear() {
        update(TextFieldValue())
    }

    fun submit(key: KeyboardKey) {
        TouchKeyboardStore.submit(KeyboardInput.Key(key))
    }

    fun reset() {
        value = TextFieldValue()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameplayKeyboardScreen(
    state: GameplayKeyboardState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var editorValue by remember { mutableStateOf(state.value.withDeleteGuard()) }
    val closeKeyboardScreen = {
        keyboardController?.hide()
        onClose()
    }

    BackHandler(onBack = closeKeyboardScreen)
    DisposableEffect(Unit) {
        onDispose { keyboardController?.hide() }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    LaunchedEffect(state.value) {
        if (editorValue.withoutDeleteGuard().text != state.value.text) {
            editorValue = state.value.withDeleteGuard()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keyboard_button)) },
                navigationIcon = {
                    IconButton(onClick = closeKeyboardScreen) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.keyboard_text_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = editorValue,
                onValueChange = { next ->
                    when {
                        next.text.startsWith(DELETE_GUARD) -> {
                            state.update(next.withoutDeleteGuard())
                        }
                        next.text.isEmpty() && state.value.text.isEmpty() -> {
                            state.submit(KeyboardKey.BACKSPACE)
                        }
                        else -> state.update(next)
                    }
                    editorValue = state.value.withDeleteGuard()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text(stringResource(R.string.keyboard_text_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { state.submit(KeyboardKey.ENTER) },
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = state::clear,
                    enabled = state.value.text.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.clear_action))
                }
                TextButton(
                    onClick = closeKeyboardScreen,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.close_action))
                }
            }
        }
    }
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
