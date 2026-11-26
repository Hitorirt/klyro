package gg.klyro.mapper.server

import android.hardware.input.InputManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.view.InputEvent
import gg.klyro.mapper.inject.PointerState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Servidor do Klyro. Roda num processo com permissão de shell, iniciado pelo app via
 * Shizuku ou Depuração sem fio:
 *
 *   CLASSPATH=<apk> app_process / gg.klyro.mapper.server.InjectServer <uid do app> <pasta das libs>
 *
 * - Injeta toques (multitoque) na tela.
 * - Lê teclado e mouse direto do /dev/input e manda para o app, sem nenhuma janela tomar o foco do jogo.
 * - Agarra o mouse quando a mira trava, para o cursor não mexer nem clicar no jogo.
 *
 * Só aceita conexões do app dono (confere o uid de quem conecta).
 * Baseado nas técnicas do XtMapper (GPL-3.0) e do scrcpy (Apache-2.0).
 */
object InjectServer {
    const val SOCKET_NAME = "klyro_inject"

    // app → servidor
    const val OP_PING: Byte = 0
    const val OP_DOWN: Byte = 1
    const val OP_MOVE: Byte = 2
    const val OP_UP: Byte = 3
    const val OP_RELEASE_ALL: Byte = 4
    const val OP_GRAB_MOUSE: Byte = 5
    const val OP_CURSOR: Byte = 6
    const val OP_TOUCH_PASS: Byte = 7
    const val OP_IGNORE_DEVICES: Byte = 8
    const val OP_QUIT: Byte = 9

    /** Ids dos toques repassados do touchscreen (separados dos ids dos controles). */
    private const val PASS_ID = 8000

    // servidor → app
    const val MSG_PONG: Byte = 1
    const val MSG_INPUT: Byte = 20
    const val MSG_FOREGROUND: Byte = 21
    const val MSG_FPS: Byte = 22

    private lateinit var inputManager: Any
    private lateinit var injectMethod: Method
    private val pointers = PointerState { event ->
        try {
            // Primeiro dedo de um toque novo: tira o "mouse pairando" dos aparelhos de mouse (no LDPlayer, o
            // VirtualMouse). Com um mouse pairando, o Android recusa toques de outro aparelho.
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) clearMouseHover(event.x, event.y)
            injectMethod.invoke(inputManager, event, 0) // 0 = assíncrono
        } catch (e: Exception) {
            System.err.println("falha ao injetar: ${e.message}")
        }
    }

    /** Aparelhos de mouse do Android (inclui os virtuais, como o VirtualMouse do LDPlayer). */
    @Volatile private var mouseDeviceIds = IntArray(0)

    private fun refreshMouseDevices() {
        val ids = runCatching {
            android.view.InputDevice.getDeviceIds().filter { id ->
                android.view.InputDevice.getDevice(id)?.supportsSource(android.view.InputDevice.SOURCE_MOUSE) == true
            }.toIntArray()
        }.getOrDefault(IntArray(0))
        if (!ids.contentEquals(mouseDeviceIds)) {
            mouseDeviceIds = ids
            println("aparelhos de mouse: " + ids.joinToString())
        }
    }

    private fun clearMouseHover(x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        for (id in mouseDeviceIds) {
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { this.id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
            val ev = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_HOVER_EXIT, 1, props, coords, 0, 0, 1f, 1f, id, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
            runCatching { injectMethod.invoke(inputManager, ev, 0) }
            ev.recycle()
        }
    }

    private val readers = HashMap<String, EvdevReader>()
    private val writeLock = Any()
    @Volatile private var client: DataOutputStream? = null
    @Volatile private var mouseGrabbed = false
    /** Identifica a instalação do app que iniciou este servidor (o app reinicia o servidor se mudar). */
    private var stamp = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        val allowedUid = args.getOrNull(0)?.toIntOrNull() ?: run {
            System.err.println("uso: InjectServer <uid> <pasta das libs>")
            exitProcess(2)
        }
        stamp = args.getOrNull(2)?.toLongOrNull() ?: 0L
        args.getOrNull(1)?.let { dir ->
            runCatching { System.load("$dir/libklyroinput.so") }
                .onSuccess { println("lib ok: $dir") }
                .onFailure { System.err.println("lib: ${it.message}") }
        }

        val cls: Class<*> = try {
            Class.forName("android.hardware.input.InputManagerGlobal") // Android 14+
        } catch (_: ClassNotFoundException) {
            InputManager::class.java
        }
        inputManager = cls.getDeclaredMethod("getInstance").invoke(null)!!
        injectMethod = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)

        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: Exception) {
            System.err.println("socket em uso: ${e.message}")
            exitProcess(1)
        }
        thread(name = "evdev-scan", isDaemon = true) { scanLoop() }
        thread(name = "fg-watch", isDaemon = true) { foregroundWatchLoop() }
        thread(name = "fps", isDaemon = true) { fpsLoop() }
        println("klyro server pronto (uid $allowedUid)")
        while (true) {
            val socket = server.accept()
            if (socket.peerCredentials.uid != allowedUid) {
                runCatching { socket.close() }
                continue
            }
            thread(name = "client") { serve(socket) }
        }
    }

    /** Procura teclados e mouses a cada 2 s (pega os que forem conectados depois). */
    private fun scanLoop() {
        while (true) {
            runCatching {
                val found = scanEvdevDevices()
                if (found.map { it.path }.toSet() != synchronized(readers) { readers.keys.toSet() }) {
                    println("dispositivos: " + found.joinToString { "${it.name} (${it.path}${if (it.isKeyboard) " teclado" else ""}${if (it.isMouse) " mouse" else ""})" })
                }
                synchronized(readers) {
                    found.forEach { dev ->
                        if (dev.path !in readers) {
                            readers[dev.path] = EvdevReader(dev, ::onInput).also {
                                it.start()
                                if (dev.isMouse && mouseGrabbed) it.grab(true)
                            }
                        }
                    }
                    val gone = readers.keys - found.map { it.path }.toSet()
                    gone.forEach { readers.remove(it)?.stop(); passScale.remove(it) }
                }
                refreshMouseDevices()
                // Touch que apareceu (ou abriu) depois do pedido de repasse.
                if (passOn) setTouchPass(true, passW, passH)
            }
            Thread.sleep(2000)
        }
    }

    @Volatile private var lastForegroundPkg = ""

    /**
     * Verifica qual app está em primeiro plano (visível na tela) a cada 750ms via permissão de shell
     * e avisa o app para esconder os controles quando o usuário sair do jogo!
     */
    private fun foregroundWatchLoop() {
        val pkgRegex = Regex("""(?:mCurrentFocus|mFocusedApp|topResumedActivity|ResumedActivity)[^\n]*?\s([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""")
        while (true) {
            runCatching {
                val out = client
                if (out != null) {
                    // Android 10+: "displays"; Android 9 (ex.: BlueStacks) só mostra o foco em "windows".
                    var text = sh("dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp'")
                    if (pkgRegex.find(text) == null) text = sh("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'")
                    val pkg = pkgRegex.find(text)?.groupValues?.getOrNull(1).orEmpty()
                    if (pkg.isNotEmpty() && pkg != lastForegroundPkg) {
                        lastForegroundPkg = pkg
                        synchronized(writeLock) {
                            out.writeByte(MSG_FOREGROUND.toInt())
                            out.writeUTF(pkg)
                            out.flush()
                        }
                    }
                } else {
                    lastForegroundPkg = ""
                }
            }
            Thread.sleep(750)
        }
    }

    // ---------- FPS do jogo ----------
    // Conta quantos quadros a camada do jogo apresentou no último segundo (dumpsys SurfaceFlinger --latency).
    private var fpsLayer = ""
    private var fpsLayerPkg = ""

    private fun sh(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val t = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return t
    }

    private fun findGameLayer(pkg: String): String {
        val layers = sh("dumpsys SurfaceFlinger --list").lines().map { it.trim() }.filter { it.contains(pkg) }
        return layers.firstOrNull { it.startsWith("SurfaceView") && !it.contains("Background") }
            ?: layers.firstOrNull { it.contains("SurfaceView") && !it.contains("Background") }
            ?: layers.lastOrNull().orEmpty()
    }

    private fun fpsLoop() {
        while (true) {
            Thread.sleep(1000)
            val out = client ?: continue
            val pkg = lastForegroundPkg
            if (pkg.isEmpty()) continue
            val fps = runCatching {
                if (pkg != fpsLayerPkg || fpsLayer.isEmpty()) {
                    fpsLayer = findGameLayer(pkg)
                    fpsLayerPkg = pkg
                }
                if (fpsLayer.isEmpty()) return@runCatching -1
                val lines = sh("dumpsys SurfaceFlinger --latency '" + fpsLayer.replace("'", "'\\''") + "'").lines()
                val now = System.nanoTime()
                var count = 0
                for (line in lines.drop(1)) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 3) continue
                    val present = parts[1].toLongOrNull() ?: continue
                    if (present <= 0 || present == Long.MAX_VALUE) continue
                    if (now - present in 0..1_000_000_000L) count++
                }
                if (count == 0 && lines.size <= 2) { fpsLayer = ""; -1 } else count
            }.getOrDefault(-1)
            runCatching {
                synchronized(writeLock) {
                    out.writeByte(MSG_FPS.toInt())
                    out.writeInt(fps)
                    out.flush()
                }
            }
        }
    }

    // ---------- Repasse do touch ----------
    // O Android não aceita dois aparelhos tocando a tela ao mesmo tempo: o toque do usuário (no LDPlayer,
    // o clique do mouse vira toque) e os toques injetados se derrubavam. Com os controles ligados, o servidor
    // segura (EVIOCGRAB) o touchscreen e injeta os toques dele junto com os do Klyro, como um único aparelho.
    private val passScale = HashMap<String, FloatArray>()
    private val slotActive = BooleanArray(16)
    private val slotDown = BooleanArray(16)
    private val slotUp = BooleanArray(16)
    private val slotMoved = BooleanArray(16)
    private val slotX = FloatArray(16)
    private val slotY = FloatArray(16)
    private var curSlot = 0

    @Volatile private var passOn = false
    @Volatile private var passW = 0f
    @Volatile private var passH = 0f

    private fun setTouchPass(on: Boolean, screenW: Float, screenH: Float) {
        passOn = on; passW = screenW; passH = screenH
        synchronized(readers) {
            for (r in readers.values) {
                if (!r.device.isTouch) continue
                if (on) {
                    if (passScale.containsKey(r.device.path)) continue
                    val mx = r.absMax(Evdev.ABS_MT_POSITION_X)
                    val my = r.absMax(Evdev.ABS_MT_POSITION_Y)
                    // Só quando o touch já está na mesma orientação da tela (ex.: LDPlayer deitado).
                    if (mx > 0 && my > 0 && (mx > my) == (screenW > screenH) && r.grab(true)) {
                        passScale[r.device.path] = floatArrayOf(screenW / (mx + 1), screenH / (my + 1))
                        println("repasse do touch ligado: " + r.device.path + " (" + (mx + 1) + "x" + (my + 1) + ")")
                    }
                } else if (passScale.remove(r.device.path) != null) {
                    r.grab(false)
                    println("repasse do touch desligado: " + r.device.path)
                }
            }
        }
        if (!on) releasePassPointers()
    }

    private fun releasePassPointers() {
        for (i in 0 until 16) {
            if (slotActive[i]) pointers.up(PASS_ID + i)
            slotActive[i] = false; slotDown[i] = false; slotUp[i] = false; slotMoved[i] = false
        }
    }

    private fun onTouch(scale: FloatArray, type: Int, code: Int, value: Int) {
        if (type == Evdev.EV_ABS) {
            when (code) {
                0x2f -> curSlot = value.coerceIn(0, 15) // ABS_MT_SLOT
                0x39 -> { // ABS_MT_TRACKING_ID: -1 = dedo saiu
                    if (value < 0) slotUp[curSlot] = true
                    else if (!slotActive[curSlot]) slotDown[curSlot] = true
                }
                Evdev.ABS_MT_POSITION_X -> { slotX[curSlot] = value * scale[0]; slotMoved[curSlot] = true }
                Evdev.ABS_MT_POSITION_Y -> { slotY[curSlot] = value * scale[1]; slotMoved[curSlot] = true }
            }
            return
        }
        if (code != 0) return // só o SYN_REPORT aplica o quadro
        for (i in 0 until 16) {
            when {
                slotUp[i] -> {
                    if (slotActive[i]) pointers.up(PASS_ID + i)
                    slotActive[i] = false
                }
                slotDown[i] -> {
                    pointers.down(PASS_ID + i, slotX[i], slotY[i])
                    slotActive[i] = true
                }
                slotMoved[i] && slotActive[i] -> pointers.move(PASS_ID + i, slotX[i], slotY[i])
            }
            slotUp[i] = false; slotDown[i] = false; slotMoved[i] = false
        }
    }

    /** Teclados/mouses que o usuário mandou ignorar (por nome). */
    @Volatile private var ignoredNames: Set<String> = emptySet()

    private fun onInput(dev: EvdevDevice, type: Int, code: Int, value: Int) {
        if (dev.name in ignoredNames && !dev.isTouch) return
        if (type == Evdev.EV_ABS || type == 0) {
            passScale[dev.path]?.let { onTouch(it, type, code, value) }
            return
        }
        // Ignora códigos de touchscreen (ex.: BTN_TOUCH = 330) que não são teclas de teclado nem botões de mouse
        if (type == Evdev.EV_KEY && code >= 0x118) return
        val out = client ?: return
        try {
            synchronized(writeLock) {
                out.writeByte(MSG_INPUT.toInt())
                out.writeByte(if (dev.isMouse && type == Evdev.EV_REL || code >= LinuxKeys.BTN_LEFT) 1 else 0)
                out.writeShort(type)
                out.writeShort(code)
                out.writeInt(value)
                out.flush()
            }
        } catch (_: Exception) {
            // app desconectado
        }
    }

    /**
     * Leva o cursor do mouse até (x, y) e encerra o hover (ACTION_HOVER_EXIT) imediatamente
     * para não deixar o InputDispatcher do Android em estado "hovering" (o que bloquearia toques).
     */
    private fun moveCursor(x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        for (action in intArrayOf(android.view.MotionEvent.ACTION_HOVER_ENTER, android.view.MotionEvent.ACTION_HOVER_MOVE, android.view.MotionEvent.ACTION_HOVER_EXIT)) {
            val ev = android.view.MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, -2, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
            try {
                injectMethod.invoke(inputManager, ev, 0)
            } catch (e: Exception) {
                System.err.println("cursor: ${e.message}")
            } finally {
                ev.recycle()
            }
        }
    }

    private fun setMouseGrab(on: Boolean) {
        mouseGrabbed = on
        // Usa exclusivamente o PointerCapture nativo do Android (igual ao KeySync) para esconder o cursor
        // e receber os movimentos relativos. Não usa EVIOCGRAB no /dev/input para nunca bloquear o InputReader
        // ou congelar os toques/teclas da tela em emuladores e celulares.
        if (!on) {
            synchronized(readers) {
                readers.values.forEach { it.grab(false) }
            }
        }
    }

    private fun serve(socket: LocalSocket) {
        val output = DataOutputStream(socket.outputStream.buffered())
        client = output
        try {
            val input = DataInputStream(socket.inputStream.buffered())
            while (true) {
                when (input.readByte()) {
                    OP_PING -> synchronized(writeLock) { output.writeByte(MSG_PONG.toInt()); output.writeLong(stamp); output.flush() }
                    OP_DOWN -> pointers.down(input.readInt(), input.readFloat(), input.readFloat())
                    OP_MOVE -> pointers.move(input.readInt(), input.readFloat(), input.readFloat())
                    OP_UP -> pointers.up(input.readInt())
                    OP_RELEASE_ALL -> pointers.releaseAll()
                    OP_GRAB_MOUSE -> setMouseGrab(input.readByte().toInt() != 0)
                    OP_CURSOR -> moveCursor(input.readFloat(), input.readFloat())
                    OP_TOUCH_PASS -> setTouchPass(input.readByte().toInt() != 0, input.readFloat(), input.readFloat())
                    OP_IGNORE_DEVICES -> {
                        ignoredNames = input.readUTF().split('\n').filter { it.isNotBlank() }.toSet()
                        println("aparelhos ignorados: " + ignoredNames.joinToString())
                    }
                    OP_QUIT -> {
                        pointers.releaseAll()
                        setMouseGrab(false)
                        exitProcess(0)
                    }
                }
            }
        } catch (_: Exception) {
            // App desconectou: solta os dedos e o mouse para nada ficar "preso".
            pointers.releaseAll()
            setMouseGrab(false)
        } finally {
            if (client === output) client = null
            runCatching { socket.close() }
        }
    }
}
