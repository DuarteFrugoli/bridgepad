package dev.jonalakas.bridgepad.ui.gamepad

import androidx.compose.ui.text.input.TextFieldValue
import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.KeyboardKey
import dev.jonalakas.bridgepad.input.touch.TouchKeyboardStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GameplayKeyboardOverlayTest {
    @Test
    fun sendClearsOnlyTheLocalMirrorAfterEnter() {
        TouchKeyboardStore.clear()
        try {
            val state = GameplayKeyboardState()
            state.update(TextFieldValue("hello"))
            assertEquals(KeyboardInput.Text("hello"), TouchKeyboardStore.consume())

            state.sendAndClear()

            assertEquals("", state.value.text)
            assertEquals(KeyboardInput.Key(KeyboardKey.ENTER), TouchKeyboardStore.consume())
            assertFalse(TouchKeyboardStore.hasPending())
        } finally {
            TouchKeyboardStore.clear()
        }
    }

    @Test
    fun appendProducesOneTextInput() {
        assertEquals(
            listOf(KeyboardInput.Text(" world")),
            keyboardInputsForChange("hello", "hello world"),
        )
    }

    @Test
    fun clearProducesOneBackspacePerVisibleCharacter() {
        assertEquals(
            List(3) { KeyboardInput.Key(KeyboardKey.BACKSPACE) },
            keyboardInputsForChange("abc", ""),
        )
    }

    @Test
    fun replacementRemovesSuffixBeforeSendingNewText() {
        assertEquals(
            listOf(
                KeyboardInput.Key(KeyboardKey.BACKSPACE),
                KeyboardInput.Key(KeyboardKey.BACKSPACE),
                KeyboardInput.Text("XY"),
            ),
            keyboardInputsForChange("abcd", "abXY"),
        )
    }

    @Test
    fun controlCharactersBecomeKeys() {
        assertEquals(
            listOf(
                KeyboardInput.Text("a"),
                KeyboardInput.Key(KeyboardKey.ENTER),
                KeyboardInput.Text("b"),
                KeyboardInput.Key(KeyboardKey.TAB),
            ),
            keyboardInputsForChange("", "a\nb\t"),
        )
    }
}
