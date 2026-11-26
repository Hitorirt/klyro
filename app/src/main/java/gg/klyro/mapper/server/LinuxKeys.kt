package gg.klyro.mapper.server

import android.view.KeyEvent

/**
 * Códigos de tecla do Linux (evdev, linux/input-event-codes.h) → códigos de tecla do Android
 * (mesma tabela do Generic.kl do Android, só as teclas de um teclado comum).
 */
object LinuxKeys {
    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112
    const val BTN_SIDE = 0x113
    const val BTN_EXTRA = 0x114

    private val map: Map<Int, Int> = buildMap {
        // Letras
        val letters = mapOf(
            30 to KeyEvent.KEYCODE_A, 48 to KeyEvent.KEYCODE_B, 46 to KeyEvent.KEYCODE_C, 32 to KeyEvent.KEYCODE_D,
            18 to KeyEvent.KEYCODE_E, 33 to KeyEvent.KEYCODE_F, 34 to KeyEvent.KEYCODE_G, 35 to KeyEvent.KEYCODE_H,
            23 to KeyEvent.KEYCODE_I, 36 to KeyEvent.KEYCODE_J, 37 to KeyEvent.KEYCODE_K, 38 to KeyEvent.KEYCODE_L,
            50 to KeyEvent.KEYCODE_M, 49 to KeyEvent.KEYCODE_N, 24 to KeyEvent.KEYCODE_O, 25 to KeyEvent.KEYCODE_P,
            16 to KeyEvent.KEYCODE_Q, 19 to KeyEvent.KEYCODE_R, 31 to KeyEvent.KEYCODE_S, 20 to KeyEvent.KEYCODE_T,
            22 to KeyEvent.KEYCODE_U, 47 to KeyEvent.KEYCODE_V, 17 to KeyEvent.KEYCODE_W, 45 to KeyEvent.KEYCODE_X,
            21 to KeyEvent.KEYCODE_Y, 44 to KeyEvent.KEYCODE_Z,
        )
        putAll(letters)
        // Números da linha de cima
        put(2, KeyEvent.KEYCODE_1); put(3, KeyEvent.KEYCODE_2); put(4, KeyEvent.KEYCODE_3); put(5, KeyEvent.KEYCODE_4)
        put(6, KeyEvent.KEYCODE_5); put(7, KeyEvent.KEYCODE_6); put(8, KeyEvent.KEYCODE_7); put(9, KeyEvent.KEYCODE_8)
        put(10, KeyEvent.KEYCODE_9); put(11, KeyEvent.KEYCODE_0)
        // F1–F12
        val f = listOf(59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88)
        f.forEachIndexed { i, code -> put(code, KeyEvent.KEYCODE_F1 + i) }
        // Controle, modificadores e símbolos (incluindo ABNT2: 86 = \ e 89 = /)
        put(1, KeyEvent.KEYCODE_ESCAPE); put(12, KeyEvent.KEYCODE_MINUS); put(13, KeyEvent.KEYCODE_EQUALS)
        put(14, KeyEvent.KEYCODE_DEL); put(15, KeyEvent.KEYCODE_TAB); put(26, KeyEvent.KEYCODE_LEFT_BRACKET)
        put(27, KeyEvent.KEYCODE_RIGHT_BRACKET); put(28, KeyEvent.KEYCODE_ENTER); put(29, KeyEvent.KEYCODE_CTRL_LEFT)
        put(39, KeyEvent.KEYCODE_SEMICOLON); put(40, KeyEvent.KEYCODE_APOSTROPHE); put(41, KeyEvent.KEYCODE_GRAVE)
        put(42, KeyEvent.KEYCODE_SHIFT_LEFT); put(43, KeyEvent.KEYCODE_BACKSLASH); put(51, KeyEvent.KEYCODE_COMMA)
        put(52, KeyEvent.KEYCODE_PERIOD); put(53, KeyEvent.KEYCODE_SLASH); put(54, KeyEvent.KEYCODE_SHIFT_RIGHT)
        put(56, KeyEvent.KEYCODE_ALT_LEFT); put(57, KeyEvent.KEYCODE_SPACE); put(58, KeyEvent.KEYCODE_CAPS_LOCK)
        put(69, KeyEvent.KEYCODE_NUM_LOCK); put(70, KeyEvent.KEYCODE_SCROLL_LOCK)
        put(86, KeyEvent.KEYCODE_BACKSLASH); put(89, KeyEvent.KEYCODE_SLASH)
        put(97, KeyEvent.KEYCODE_CTRL_RIGHT); put(99, KeyEvent.KEYCODE_SYSRQ); put(100, KeyEvent.KEYCODE_ALT_RIGHT)
        put(102, KeyEvent.KEYCODE_MOVE_HOME); put(103, KeyEvent.KEYCODE_DPAD_UP); put(104, KeyEvent.KEYCODE_PAGE_UP)
        put(105, KeyEvent.KEYCODE_DPAD_LEFT); put(106, KeyEvent.KEYCODE_DPAD_RIGHT); put(107, KeyEvent.KEYCODE_MOVE_END)
        put(108, KeyEvent.KEYCODE_DPAD_DOWN); put(109, KeyEvent.KEYCODE_PAGE_DOWN); put(110, KeyEvent.KEYCODE_INSERT)
        put(111, KeyEvent.KEYCODE_FORWARD_DEL); put(119, KeyEvent.KEYCODE_BREAK)
        put(125, KeyEvent.KEYCODE_META_LEFT); put(126, KeyEvent.KEYCODE_META_RIGHT); put(127, KeyEvent.KEYCODE_MENU)
        // Teclado numérico
        val numpad = listOf(82, 79, 80, 81, 75, 76, 77, 71, 72, 73)
        numpad.forEachIndexed { i, code -> put(code, KeyEvent.KEYCODE_NUMPAD_0 + i) }
        put(96, KeyEvent.KEYCODE_NUMPAD_ENTER); put(78, KeyEvent.KEYCODE_NUMPAD_ADD); put(74, KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
        put(55, KeyEvent.KEYCODE_NUMPAD_MULTIPLY); put(98, KeyEvent.KEYCODE_NUMPAD_DIVIDE); put(83, KeyEvent.KEYCODE_NUMPAD_DOT)
    }

    /** Código Android da tecla, ou [KeyEvent.KEYCODE_UNKNOWN] se não for uma tecla conhecida. */
    fun toAndroid(linuxCode: Int): Int = map[linuxCode] ?: KeyEvent.KEYCODE_UNKNOWN
}
