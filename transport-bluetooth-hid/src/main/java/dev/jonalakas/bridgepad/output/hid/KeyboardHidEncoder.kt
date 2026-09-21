package dev.jonalakas.bridgepad.output.hid

import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.KeyboardKey
import dev.jonalakas.bridgepad.core.ports.KeyboardModifier

object KeyboardHidEncoder {
    fun encode(input: KeyboardInput): List<ByteArray> = when (input) {
        is KeyboardInput.Text -> input.value.flatMap { character ->
            encodeCharacter(character)?.reports().orEmpty()
        }
        is KeyboardInput.Key -> keyStroke(input.key).reports()
        is KeyboardInput.Shortcut -> keyStroke(input.key, input.modifiers).reports()
    }

    fun modifierReport(modifiers: Set<KeyboardModifier>): ByteArray =
        byteArrayOf(modifiers.hidBits.toByte(), 0, 0, 0, 0, 0, 0, 0)

    private fun encodeCharacter(character: Char): KeyStroke? {
        val normalized = normalizePortugueseCharacter(character)
        if (normalized in 'a'..'z') return KeyStroke(normalized - 'a' + 0x04)
        if (normalized in 'A'..'Z') return KeyStroke(normalized - 'A' + 0x04, SHIFT)
        if (normalized in '1'..'9') return KeyStroke(normalized - '1' + 0x1E)
        return when (normalized) {
            '0' -> KeyStroke(0x27)
            '\n', '\r' -> KeyStroke(0x28)
            '\t' -> KeyStroke(0x2B)
            ' ' -> KeyStroke(0x2C)
            '-' -> KeyStroke(0x2D)
            '_' -> KeyStroke(0x2D, SHIFT)
            '=' -> KeyStroke(0x2E)
            '+' -> KeyStroke(0x2E, SHIFT)
            '[' -> KeyStroke(0x2F)
            '{' -> KeyStroke(0x2F, SHIFT)
            ']' -> KeyStroke(0x30)
            '}' -> KeyStroke(0x30, SHIFT)
            '\\' -> KeyStroke(0x31)
            '|' -> KeyStroke(0x31, SHIFT)
            ';' -> KeyStroke(0x33)
            ':' -> KeyStroke(0x33, SHIFT)
            '\'' -> KeyStroke(0x34)
            '"' -> KeyStroke(0x34, SHIFT)
            '`' -> KeyStroke(0x35)
            '~' -> KeyStroke(0x35, SHIFT)
            ',' -> KeyStroke(0x36)
            '<' -> KeyStroke(0x36, SHIFT)
            '.' -> KeyStroke(0x37)
            '>' -> KeyStroke(0x37, SHIFT)
            '/' -> KeyStroke(0x38)
            '?' -> KeyStroke(0x38, SHIFT)
            '!' -> KeyStroke(0x1E, SHIFT)
            '@' -> KeyStroke(0x1F, SHIFT)
            '#' -> KeyStroke(0x20, SHIFT)
            '$' -> KeyStroke(0x21, SHIFT)
            '%' -> KeyStroke(0x22, SHIFT)
            '^' -> KeyStroke(0x23, SHIFT)
            '&' -> KeyStroke(0x24, SHIFT)
            '*' -> KeyStroke(0x25, SHIFT)
            '(' -> KeyStroke(0x26, SHIFT)
            ')' -> KeyStroke(0x27, SHIFT)
            else -> null
        }
    }

    private fun keyStroke(
        key: KeyboardKey,
        modifiers: Set<KeyboardModifier> = emptySet(),
    ): KeyStroke = KeyStroke(
        when (key) {
            KeyboardKey.BACKSPACE -> 0x2A
            KeyboardKey.D -> 0x07
            KeyboardKey.ENTER -> 0x28
            KeyboardKey.LEFT -> 0x50
            KeyboardKey.M -> 0x10
            KeyboardKey.RIGHT -> 0x4F
            KeyboardKey.TAB -> 0x2B
            KeyboardKey.ESCAPE -> 0x29
        },
        modifiers.hidBits,
    )

    private fun normalizePortugueseCharacter(character: Char): Char = when (character) {
        'á', 'à', 'â', 'ã', 'ä' -> 'a'
        'Á', 'À', 'Â', 'Ã', 'Ä' -> 'A'
        'é', 'è', 'ê', 'ë' -> 'e'
        'É', 'È', 'Ê', 'Ë' -> 'E'
        'í', 'ì', 'î', 'ï' -> 'i'
        'Í', 'Ì', 'Î', 'Ï' -> 'I'
        'ó', 'ò', 'ô', 'õ', 'ö' -> 'o'
        'Ó', 'Ò', 'Ô', 'Õ', 'Ö' -> 'O'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'Ú', 'Ù', 'Û', 'Ü' -> 'U'
        'ç' -> 'c'
        'Ç' -> 'C'
        else -> character
    }

    private fun KeyStroke.reports(): List<ByteArray> = listOf(
        byteArrayOf(modifier.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0),
        ByteArray(KEYBOARD_REPORT_SIZE),
    )

    private data class KeyStroke(val usage: Int, val modifier: Int = 0)

    private val Set<KeyboardModifier>.hidBits: Int
        get() = fold(0) { bits, modifier ->
            bits or when (modifier) {
                KeyboardModifier.ALT -> ALT
                KeyboardModifier.CONTROL -> CONTROL
                KeyboardModifier.META -> META
                KeyboardModifier.SHIFT -> SHIFT
            }
        }

    private const val CONTROL = 0x01
    private const val SHIFT = 0x02
    private const val ALT = 0x04
    private const val META = 0x08
    private const val KEYBOARD_REPORT_SIZE = 8
}
