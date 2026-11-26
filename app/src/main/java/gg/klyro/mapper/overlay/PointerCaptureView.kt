package gg.klyro.mapper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import gg.klyro.mapper.mapping.MouseButton

/**
 * Janela invisível (1×1) usada enquanto o mouse está travado: pede ao Android o "pointer capture",
 * que esconde o cursor do mouse no Android/emulador e entrega o movimento relativo (dx, dy) e os cliques.
 */
@SuppressLint("ViewConstructor")
class PointerCaptureView(
    context: Context,
    private val onMove: (Float, Float) -> Unit,
    private val onButton: (MouseButton, Boolean) -> Unit,
    private val onKey: (KeyEvent) -> Boolean = { false },
    private val onLost: () -> Unit = {},
) : View(context) {

    private var lastButtonState = 0
    private var lastAbsX = Float.NaN
    private var lastAbsY = Float.NaN
    @Volatile var active = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        setOnCapturedPointerListener { _, event -> handleCapturedEvent(event) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            if (active) {
                requestFocus()
                requestPointerCapture()
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!active) return
        if (hasWindowFocus) {
            lastAbsX = Float.NaN
            lastAbsY = Float.NaN
            requestPointerCapture()
            postDelayed({ if (active && hasWindowFocus && !hasPointerCapture()) requestPointerCapture() }, 80)
        }
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean = handleCapturedEvent(event)

    private fun handleCapturedEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val relX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val relY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (relX != 0f || relY != 0f) {
                    for (h in 0 until event.historySize) {
                        val hx = event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, h)
                        val hy = event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, h)
                        if (hx != 0f || hy != 0f) onMove(hx, hy)
                    }
                    onMove(relX, relY)
                } else {
                    // Emuladores como LDPlayer/BlueStacks podem mandar deltas em event.x/event.y ou coordenadas em rawX/rawY
                    val x = event.x
                    val y = event.y
                    if (kotlin.math.abs(x) < 350f && kotlin.math.abs(y) < 350f && (x != 0f || y != 0f)) {
                        for (h in 0 until event.historySize) {
                            onMove(event.getHistoricalX(h), event.getHistoricalY(h))
                        }
                        onMove(x, y)
                    } else {
                        val rx = event.rawX
                        val ry = event.rawY
                        if (!lastAbsX.isNaN() && !lastAbsY.isNaN()) {
                            val dx = rx - lastAbsX
                            val dy = ry - lastAbsY
                            if (dx != 0f || dy != 0f) onMove(dx, dy)
                        }
                        lastAbsX = rx
                        lastAbsY = ry
                    }
                }
                syncButtonState(event.buttonState)
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                button(event.actionButton)?.let { onButton(it, true) }
                syncButtonState(event.buttonState)
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                button(event.actionButton)?.let { onButton(it, false) }
                syncButtonState(event.buttonState)
            }
            MotionEvent.ACTION_DOWN -> {
                if (event.buttonState != 0) syncButtonState(event.buttonState)
                else onButton(MouseButton.Left, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (lastButtonState != 0) syncButtonState(0)
                else onButton(MouseButton.Left, false)
            }
        }
        return true
    }

    private fun syncButtonState(state: Int) {
        val changed = state xor lastButtonState
        if (changed == 0) return
        lastButtonState = state
        val checks = listOf(
            MotionEvent.BUTTON_PRIMARY to MouseButton.Left,
            MotionEvent.BUTTON_SECONDARY to MouseButton.Right,
            MotionEvent.BUTTON_TERTIARY to MouseButton.Middle,
        )
        for ((mask, btn) in checks) {
            if (changed and mask != 0) {
                onButton(btn, state and mask != 0)
            }
        }
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        if (!hasCapture && active) {
            onLost()
            postDelayed({
                if (active && hasWindowFocus()) requestPointerCapture()
            }, 60)
        }
    }

    private fun button(b: Int) = when (b) {
        MotionEvent.BUTTON_PRIMARY -> MouseButton.Left
        MotionEvent.BUTTON_SECONDARY -> MouseButton.Right
        MotionEvent.BUTTON_TERTIARY -> MouseButton.Middle
        else -> null
    }
}
