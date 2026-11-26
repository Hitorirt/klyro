package gg.klyro.mapper.server

import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Funções nativas (libklyroinput.so) para abrir e agarrar dispositivos de entrada. */
object Evdev {
    @JvmStatic external fun open(path: String): Int
    @JvmStatic external fun grab(fd: Int, on: Boolean): Boolean
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun absMax(fd: Int, code: Int): Int

    const val EV_KEY = 1
    const val EV_REL = 2
    const val EV_ABS = 3
    const val REL_X = 0
    const val REL_Y = 1
    const val REL_HWHEEL = 6
    const val REL_WHEEL = 8
    const val ABS_X = 0
    const val ABS_Y = 1
    const val ABS_MT_POSITION_X = 0x35
    const val ABS_MT_POSITION_Y = 0x36
}

/** Um teclado ou mouse encontrado em /proc/bus/input/devices. */
data class EvdevDevice(val name: String, val path: String, val isKeyboard: Boolean, val isMouse: Boolean, val isTouch: Boolean = false)

/** Lê /proc/bus/input/devices e devolve os teclados e mouses (é o que o kernel registrou de verdade). */
fun scanEvdevDevices(): List<EvdevDevice> {
    val text = runCatching { File("/proc/bus/input/devices").readText() }.getOrDefault("")
    return text.split("\n\n").mapNotNull { block ->
        var name = ""
        var event: String? = null
        var ev = 0L
        var key = emptyList<Long>()
        var rel = 0L
        var abs = 0L
        block.lines().forEach { line ->
            when {
                line.startsWith("N: Name=") -> name = line.removePrefix("N: Name=").trim('"')
                line.startsWith("H: Handlers=") -> event = line.split(' ', '=').firstOrNull { it.startsWith("event") }
                line.startsWith("B: EV=") -> ev = line.removePrefix("B: EV=").trim().toLongOrNull(16) ?: 0
                line.startsWith("B: KEY=") -> key = line.removePrefix("B: KEY=").trim().split(' ').map { it.toULongOrNull(16)?.toLong() ?: 0 }
                line.startsWith("B: REL=") -> rel = line.removePrefix("B: REL=").trim().toLongOrNull(16) ?: 0
                line.startsWith("B: ABS=") -> abs = line.removePrefix("B: ABS=").trim().split(' ').firstOrNull()?.toULongOrNull(16)?.toLong() ?: 0
            }
        }
        val path = event?.let { "/dev/input/$it" } ?: return@mapNotNull null
        // Cada palavra do bitmap tem o tamanho de um "long" do kernel (64 bits em aparelhos 64-bit).
        val bits = if (Process.is64Bit()) 64 else 32
        fun hasKey(code: Int): Boolean {
            val idx = key.size - 1 - code / bits
            return idx in key.indices && (key[idx] ushr (code % bits)) and 1L == 1L
        }
        val hasEvKey = ev and (1L shl Evdev.EV_KEY) != 0L
        val hasEvRel = ev and (1L shl Evdev.EV_REL) != 0L
        val hasEvAbs = ev and (1L shl Evdev.EV_ABS) != 0L
        val keyboard = hasEvKey && hasKey(30) && hasKey(17) && hasKey(57) // A, W, Espaço
        val isTouch = hasEvAbs && (hasKey(0x14a) || name.contains("Touch", ignoreCase = true) || name == "input")
        // Mouse físico (USB/Bluetooth) ou de emulador (ImExPS/2, VirtualBox mouse, etc.).
        val mouse = hasEvKey && hasKey(LinuxKeys.BTN_LEFT) && !name.contains("Touch", ignoreCase = true) &&
            ((hasEvRel && rel != 0L) || hasEvAbs)
        if (!keyboard && !mouse) null else EvdevDevice(name, path, keyboard, mouse, isTouch)
    }
}

/**
 * Lê um dispositivo e repassa cada evento (tipo, código, valor) para [onEvent]. Se o dispositivo some
 * (desconectou), a thread termina sozinha.
 */
class EvdevReader(val device: EvdevDevice, private val onEvent: (EvdevDevice, Int, Int, Int) -> Unit) {
    @Volatile private var fd = -1
    @Volatile private var stopped = false

    fun start() = thread(name = "evdev-${device.path}") {
        val size = if (Process.is64Bit()) 24 else 16
        val buf = ByteArray(size)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        fd = try { Evdev.open(device.path) } catch (t: Throwable) { System.err.println("open ${device.path}: $t"); -1 }
        if (fd < 0) {
            System.err.println("não abriu ${device.path}")
            return@thread
        }
        val input = FileInputStream(ParcelFileDescriptor.adoptFd(fd).fileDescriptor)
        try {
            while (!stopped) {
                var off = 0
                while (off < size) {
                    val n = input.read(buf, off, size - off)
                    if (n < 0) return@thread
                    off += n
                }
                val base = size - 8
                val type = bb.getShort(base).toInt() and 0xFFFF
                val code = bb.getShort(base + 2).toInt() and 0xFFFF
                val value = bb.getInt(base + 4)
                if (type == Evdev.EV_KEY || type == Evdev.EV_REL ||
                    (device.isTouch && (type == Evdev.EV_ABS || type == 0))
                ) onEvent(device, type, code, value)
            }
        } catch (_: Exception) {
            // dispositivo desconectado
        } finally {
            runCatching { input.close() }
        }
    }

    fun grab(on: Boolean): Boolean = fd >= 0 && Evdev.grab(fd, on)

    fun absMax(code: Int): Int = if (fd >= 0) Evdev.absMax(fd, code) else -1

    fun stop() {
        stopped = true
        grab(false)
    }
}
