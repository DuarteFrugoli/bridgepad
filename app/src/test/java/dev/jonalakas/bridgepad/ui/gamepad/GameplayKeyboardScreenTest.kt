package dev.jonalakas.bridgepad.ui.gamepad

import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.KeyboardKey
import org.junit.Assert.assertEquals
import org.junit.Test

class GameplayKeyboardScreenTest {
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
