package gg.klyro.mapper.mapping

import android.view.KeyEvent

/** Nome curto de uma tecla para mostrar no controle (ex.: KEYCODE_SPACE → ␣, KEYCODE_W → W). */
fun keyLabel(keyCode: Int): String =
    KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace("_", " ").let {
        when (it) {
            "SPACE" -> "SPACE"
            "SHIFT LEFT", "SHIFT RIGHT" -> "Shift"
            "CTRL LEFT", "CTRL RIGHT" -> "Ctrl"
            "ALT LEFT", "ALT RIGHT" -> "Alt"
            "META LEFT", "META RIGHT" -> "Win"
            "DPAD UP" -> "↑"
            "DPAD DOWN" -> "↓"
            "DPAD LEFT" -> "←"
            "DPAD RIGHT" -> "→"
            "GRAVE" -> "`"
            "ENTER", "NUMPAD ENTER" -> "Enter"
            "TAB" -> "Tab"
            "ESCAPE" -> "Esc"
            "DEL" -> "⌫"
            "FORWARD DEL" -> "Del"
            "CAPS LOCK" -> "Caps"
            "NUM LOCK" -> "NumLk"
            "SCROLL LOCK" -> "ScrLk"
            "SYSRQ" -> "PrtSc"
            "BREAK" -> "Pause"
            "LEFT BRACKET" -> "["
            "RIGHT BRACKET" -> "]"
            "BACKSLASH" -> "\\"
            "SEMICOLON" -> ";"
            "APOSTROPHE" -> "'"
            "COMMA" -> ","
            "PERIOD" -> "."
            "SLASH" -> "/"
            "MINUS" -> "-"
            "EQUALS" -> "="
            else -> it.removePrefix("NUMPAD ").lowercase().replaceFirstChar { c -> c.uppercase() }.take(6)
        }
    }

/** Teclas que são só modificadores. */
fun isModifierKey(code: Int) = code in setOf(
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
    KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
)

/** Texto da atribuição: "W", "L-Shift", "Mouse-L", "Mouse-R", "Empty" se ainda não tem. */
fun InputBinding.label(): String {
    if (!isSet) return "Empty"
    return when (mouse) {
        MouseButton.Left -> "Mouse-L"
        MouseButton.Right -> "Mouse-R"
        MouseButton.Middle -> "Mouse-M"
        MouseButton.WheelUp -> "Wheel ↑"
        MouseButton.WheelDown -> "Wheel ↓"
        MouseButton.None -> buildString {
            if (modifiers and KeyEvent.META_CTRL_ON != 0) append("Ctrl+")
            if (modifiers and KeyEvent.META_SHIFT_ON != 0) append("Shift+")
            if (modifiers and KeyEvent.META_ALT_ON != 0) append("Alt+")
            append(keyLabel(keyCode))
        }
    }
}
