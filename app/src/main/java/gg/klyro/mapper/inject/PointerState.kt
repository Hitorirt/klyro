package gg.klyro.mapper.inject

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Estado dos dedos virtuais na tela e montagem dos MotionEvents de multitoque.
 * Usado tanto pelo servidor shell (ADB) quanto pela injeção via Shizuku.
 *
 * Cada dedo tem um "slot" fixo enquanto está na tela, para o jogo não achar que os outros dedos pularam
 * quando um deles é solto.
 */
class PointerState(private val inject: (MotionEvent) -> Unit) {

    private class Pointer(val id: Int, val slot: Int, var x: Float, var y: Float)

    private val pointers = ArrayList<Pointer>()
    private var downTime = 0L

    private fun freeSlot(): Int = (0 until MAX_POINTERS).first { s -> pointers.none { it.slot == s } }

    @Synchronized
    fun down(id: Int, x: Float, y: Float) {
        if (pointers.any { it.id == id }) return move(id, x, y)
        if (pointers.size >= MAX_POINTERS) return
        if (pointers.isEmpty()) downTime = SystemClock.uptimeMillis()
        pointers.add(Pointer(id, freeSlot(), x, y))
        val index = pointers.size - 1
        send(if (pointers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    @Synchronized
    fun move(id: Int, x: Float, y: Float) {
        val p = pointers.firstOrNull { it.id == id } ?: return
        p.x = x
        p.y = y
        send(MotionEvent.ACTION_MOVE)
    }

    @Synchronized
    fun up(id: Int) {
        val index = pointers.indexOfFirst { it.id == id }
        if (index < 0) return
        send(if (pointers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        pointers.removeAt(index)
    }

    @Synchronized
    fun releaseAll() {
        while (pointers.isNotEmpty()) up(pointers.last().id)
    }

    private fun send(action: Int) {
        val count = pointers.size
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].slot
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x
                y = pointers[i].y
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, count, props, coords,
            0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            inject(event)
        } finally {
            event.recycle()
        }
    }

    companion object {
        const val MAX_POINTERS = 10
    }
}
