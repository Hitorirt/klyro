package gg.klyro.mapper.engine

import android.os.SystemClock
import android.view.KeyEvent
import gg.klyro.mapper.inject.RawInput
import gg.klyro.mapper.inject.TouchInjector
import gg.klyro.mapper.mapping.MouseButton
import gg.klyro.mapper.mapping.normalizeKeyCode
import gg.klyro.mapper.server.Evdev
import gg.klyro.mapper.server.LinuxKeys
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tecla apertada (código Android + Ctrl/Shift/Alt), entregue ao editor para atribuição. */
data class KeyPress(val keyCode: Int, val meta: Int, val mouse: MouseButton = MouseButton.None, val up: Boolean = false)

/**
 * Recebe o teclado/mouse lido pelo servidor shell e pelo PointerCapture do Android e decide
 * para onde vai: editor (atribuir teclas) ou motor (jogar). Também controla a trava do mouse.
 */
class InputRouter(private val engine: MappingEngine, private val injector: TouchInjector) {

    private val _editorKeys = MutableSharedFlow<KeyPress>(extraBufferCapacity = 32)
    val editorKeys: SharedFlow<KeyPress> = _editorKeys.asSharedFlow()

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Editor aberto: as teclas viram atribuição e o motor fica parado. */
    @Volatile var editing = false
        set(value) {
            field = value
            if (value) setLocked(false)
            synchronized(keysDown) { keysDown.clear() }
        }

    /** Controles ativos (desligar para digitar no chat do jogo). */
    /** Chamado pela tecla de liga/desliga (o overlay troca o estado e a tela). */
    @Volatile var onToggleControls: (() -> Unit)? = null
    /** Chamado pela parada de emergência (Shift + Esc + K). */
    @Volatile var onEmergency: (() -> Unit)? = null

    @Volatile var controlsOn = false
        set(value) {
            // Repasse do touch acompanha os controles (ver InjectServer.setTouchPass).
            if (field != value) injector.touchPassthrough(value)
            field = value
            if (!value) setLocked(false)
            engine.releaseAll()
            synchronized(keysDown) { keysDown.clear() }
        }

    private var meta = 0
    /** Teclas atualmente pressionadas (normalizadas) para nunca disparar o toggle de trava duas vezes no mesmo aperto. */
    private val keysDown = HashSet<Int>()
    @Volatile private var lastRawKeyTime = 0L
    @Volatile private var lastRawKeyCode = KeyEvent.KEYCODE_UNKNOWN
    @Volatile private var lastLockToggleTime = 0L
    @Volatile private var lastEvdevMoveTime = 0L
    @Volatile private var lastCapturedMoveTime = 0L
    @Volatile private var lastMouseButtonTime = 0L

    fun setLocked(on: Boolean) {
        if (_locked.value == on) return
        lastLockToggleTime = SystemClock.uptimeMillis()
        _locked.value = on
        engine.releaseAll()
        injector.grabMouse(on)
    }

    private fun toggleLock() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLockToggleTime < 280L) return
        setLocked(!_locked.value)
    }

    fun onRaw(ev: RawInput) {
        when (ev.type) {
            Evdev.EV_KEY -> if (ev.code >= LinuxKeys.BTN_LEFT) onMouseButton(ev) else onKey(ev)
            Evdev.EV_REL -> if (ev.code == Evdev.REL_WHEEL) {
                val dir = ev.value.coerceIn(-1, 1)
                if (dir == 0) return
                if (editing) _editorKeys.tryEmit(KeyPress(KeyEvent.KEYCODE_UNKNOWN, meta, if (dir > 0) MouseButton.WheelUp else MouseButton.WheelDown))
                else if (controlsOn) engine.onWheel(dir)
            } else if (_locked.value && !editing && controlsOn) {
                val now = SystemClock.uptimeMillis()
                // Se o PointerCapture já está entregando os deltas do mouse, evita somar duplicado.
                if (now - lastCapturedMoveTime < 40L) return
                lastEvdevMoveTime = now
                when (ev.code) {
                    Evdev.REL_X -> engine.onMouseMove(ev.value.toFloat(), 0f)
                    Evdev.REL_Y -> engine.onMouseMove(0f, ev.value.toFloat())
                }
            }
        }
    }

    /**
     * Reserva para emuladores que interceptam certas teclas (ex.: Ctrl, F1, Esc)
     * e as entregam como [KeyEvent] do Android para a janela em foco em vez do /dev/input.
     */
    fun onAndroidKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_UNKNOWN || code == KeyEvent.KEYCODE_BACK) return false
        val norm = normalizeKeyCode(code)
        val now = SystemClock.uptimeMillis()
        val down = event.action == KeyEvent.ACTION_DOWN
        val repeat = event.repeatCount > 0

        if (now - lastRawKeyTime < 180L && norm == normalizeKeyCode(lastRawKeyCode)) {
            return true
        }
        val freshDown = synchronized(keysDown) {
            if (down) keysDown.add(norm) else { keysDown.remove(norm); false }
        }
        updateMeta(code, down)

        if (editing) {
            if (down && !repeat && freshDown) {
                android.util.Log.i("InputRouter", "editor(android): tecla $code meta $meta")
                _editorKeys.tryEmit(KeyPress(code, meta))
            }
            return true
        }
        if (!controlsOn) return false
        if (engine.isLockKey(code, meta)) {
            if (down && !repeat && freshDown) toggleLock()
            return true
        }
        return engine.onKey(code, meta, down, repeat || !freshDown)
    }

    private fun onKey(ev: RawInput) {
        val code = LinuxKeys.toAndroid(ev.code)
        if (code == KeyEvent.KEYCODE_UNKNOWN) return
        val norm = normalizeKeyCode(code)
        val down = ev.value != 0
        val repeat = ev.value == 2
        lastRawKeyTime = SystemClock.uptimeMillis()
        lastRawKeyCode = code

        val freshDown = synchronized(keysDown) {
            if (down) {
                if (repeat) false else keysDown.add(norm)
            } else {
                keysDown.remove(norm)
                false
            }
        }
        updateMeta(code, down)

        if (editing) {
            if (down && !repeat && freshDown) {
                android.util.Log.i("InputRouter", "editor: tecla $code meta $meta")
                _editorKeys.tryEmit(KeyPress(code, meta))
            }
            return
        }
        // Parada de emergência: Shift + Esc + K solta tudo e desliga os controles.
        if (down && !repeat && synchronized(keysDown) {
                keysDown.contains(KeyEvent.KEYCODE_SHIFT_LEFT) && keysDown.contains(KeyEvent.KEYCODE_ESCAPE) && keysDown.contains(KeyEvent.KEYCODE_K)
            }
        ) {
            onEmergency?.invoke()
            return
        }
        // Tecla que liga/desliga os controles (para digitar no chat sem abrir o menu).
        val toggle = gg.klyro.mapper.mapping.Hotkeys.toggleKey
        if (toggle != 0 && norm == normalizeKeyCode(toggle)) {
            if (down && !repeat && freshDown) onToggleControls?.invoke()
            return
        }
        if (!controlsOn) return
        if (engine.isLockKey(code, meta)) {
            if (down && !repeat && freshDown) toggleLock()
            return
        }
        engine.onKey(code, meta, down, repeat || (down && !freshDown))
    }

    private fun onMouseButton(ev: RawInput) {
        // Ignora BTN_TOUCH (0x14a = 330) vindo do touchscreen virtual do emulador para não confundir com botão do mouse!
        if (ev.code >= 0x118 || ev.value == 2) return
        val button = when (ev.code) {
            LinuxKeys.BTN_LEFT -> MouseButton.Left
            LinuxKeys.BTN_RIGHT -> MouseButton.Right
            LinuxKeys.BTN_MIDDLE -> MouseButton.Middle
            else -> return
        }
        val down = ev.value != 0
        if (editing) {
            if (down) _editorKeys.tryEmit(KeyPress(KeyEvent.KEYCODE_UNKNOWN, meta, button))
            return
        }
        if (!controlsOn) return
        val now = SystemClock.uptimeMillis()
        if (now - lastMouseButtonTime < 35L) return
        lastMouseButtonTime = now

        if (engine.isLockButton(button)) {
            if (down) toggleLock()
            return
        }
        if (!_locked.value) return
        engine.onMouseButton(button, down)
    }

    /** Movimento vindo do "pointer capture" do Android (usado por emuladores como BlueStacks/LDPlayer). */
    fun onCapturedMove(dx: Float, dy: Float) {
        if (!_locked.value || editing || !controlsOn) return
        lastCapturedMoveTime = SystemClock.uptimeMillis()
        engine.onMouseMove(dx, dy)
    }

    /** Clique vindo do "pointer capture". */
    fun onCapturedButton(button: MouseButton, down: Boolean) {
        if (editing) {
            if (down) _editorKeys.tryEmit(KeyPress(KeyEvent.KEYCODE_UNKNOWN, meta, button))
            return
        }
        if (!_locked.value || !controlsOn) return
        val now = SystemClock.uptimeMillis()
        if (now - lastMouseButtonTime < 35L) return
        lastMouseButtonTime = now

        if (engine.isLockButton(button)) {
            if (down) toggleLock()
            return
        }
        engine.onMouseButton(button, down)
    }

    private fun updateMeta(code: Int, down: Boolean) {
        val bit = when (code) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
            else -> return
        }
        meta = if (down) meta or bit else meta and bit.inv()
    }
}
