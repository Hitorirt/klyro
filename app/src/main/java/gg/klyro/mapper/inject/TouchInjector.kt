package gg.klyro.mapper.inject

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import gg.klyro.mapper.adb.AdbRepository
import gg.klyro.mapper.adb.AdbStatus
import gg.klyro.mapper.data.ActivationMethod
import gg.klyro.mapper.data.Settings
import gg.klyro.mapper.server.InjectServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Por onde o servidor foi iniciado. */
enum class Backend { None, Shizuku, Adb }

data class InjectStatus(val backend: Backend = Backend.None, val error: String? = null) {
    val ready get() = backend != Backend.None
}

/** Evento cru de teclado/mouse lido pelo servidor (códigos do Linux). */
data class RawInput(val type: Int, val code: Int, val value: Int)

/**
 * Conversa com o [InjectServer] (processo shell): manda toques e recebe o teclado/mouse.
 * O servidor é iniciado pelo Shizuku (método principal) ou pela Depuração sem fio.
 */
class TouchInjector private constructor(private val context: Context) {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "touch-injector") }
    private var socket: LocalSocket? = null
    private var out: DataOutputStream? = null
    private val listeners = CopyOnWriteArrayList<(RawInput) -> Unit>()

    private val _status = MutableStateFlow(InjectStatus())
    val status: StateFlow<InjectStatus> = _status.asStateFlow()

    /** Pacote do app que está aberto na tela agora (atualizado pelo servidor shell). */
    private val _foregroundPackage = MutableStateFlow("")
    val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

    /** FPS do jogo em primeiro plano (-1 = não deu para medir). */
    private val _gameFps = MutableStateFlow(-1)
    val gameFps: StateFlow<Int> = _gameFps.asStateFlow()

    fun addInputListener(l: (RawInput) -> Unit) { listeners.add(l) }
    fun removeInputListener(l: (RawInput) -> Unit) { listeners.remove(l) }

    /** Sobe o servidor pelo método escolhido (se ainda não estiver rodando) e conecta. */
    fun start() = io.execute {
        if (out != null) return@execute
        Log.i(TAG, "start: método ${Settings(context).activationMethod}")
        val method = Settings(context).activationMethod
        if (connect(method)) return@execute
        val launched = when (method) {
            ActivationMethod.Shizuku -> launchWithShizuku()
            ActivationMethod.WirelessDebugging -> launchWithAdb()
        }
        if (!launched) return@execute
        repeat(28) {
            Thread.sleep(250)
            if (connect(method)) return@execute
        }
        Log.w(TAG, "o serviço não respondeu ao conectar")
        _status.value = InjectStatus(error = "o serviço não respondeu")
    }

    /** Desliga e liga de novo (ao trocar o método de ativação). */
    fun restart() {
        stop()
        start()
    }

    /** Guarda o processo do Shizuku (se ele for coletado, o Shizuku pode encerrar o servidor). */
    private var shizukuProcess: Any? = null

    private fun installStamp(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    private fun killPattern(): String = InjectServer::class.java.name.replace(".InjectServer", ".[I]njectServer")

    private fun serverCommand(): String {
        val ai = context.applicationInfo
        // Saída do servidor num arquivo, para diagnóstico (adb shell cat /data/local/tmp/klyro-server.log).
        return "CLASSPATH='${ai.sourceDir}' app_process / ${InjectServer::class.java.name} ${ai.uid} '${ai.nativeLibraryDir}' ${installStamp()} > $LOG_PATH 2>&1"
    }

    private fun launchWithShizuku(): Boolean {
        if (ShizukuGate.state(context) != ShizukuState.Ready) {
            _status.value = InjectStatus(error = "Shizuku não está pronto")
            return false
        }
        return try {
            // Shizuku.newProcess é privado na API 13; é o jeito de rodar um processo como shell pelo Shizuku.
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            // 1) Encerra um servidor antigo num processo separado: se o pkill e o início do servidor
            //    estivessem na mesma linha, o pkill acharia o nome da classe nela e mataria o próprio comando.
            (m.invoke(null, arrayOf("sh", "-c", "pkill -f '${killPattern()}'"), null, null) as? Process)?.waitFor()
            // 2) Inicia o servidor.
            Log.i(TAG, "iniciando servidor pelo Shizuku")
            shizukuProcess = m.invoke(null, arrayOf("sh", "-c", serverCommand()), null, null)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "shizuku", t)
            _status.value = InjectStatus(error = "Shizuku: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun launchWithAdb(): Boolean {
        val adb = AdbRepository.get(context)
        if (adb.status.value != AdbStatus.Connected) {
            _status.value = InjectStatus(error = "ADB desconectado")
            return false
        }
        Log.i(TAG, "iniciando servidor pela depuração sem fio")
        adb.execute("pkill -f '${killPattern()}'; nohup sh -c \"${serverCommand()}\" >/dev/null 2>&1 &")
        return true
    }

    fun down(id: Int, x: Float, y: Float) = send(InjectServer.OP_DOWN) { writeInt(id); writeFloat(x); writeFloat(y) }
    fun move(id: Int, x: Float, y: Float) = send(InjectServer.OP_MOVE) { writeInt(id); writeFloat(x); writeFloat(y) }
    fun up(id: Int) = send(InjectServer.OP_UP) { writeInt(id) }
    fun releaseAll() = send(InjectServer.OP_RELEASE_ALL) {}

    /** Põe o cursor do mouse num ponto da tela (ao destravar). */
    fun cursor(x: Float, y: Float) = send(InjectServer.OP_CURSOR) { writeFloat(x); writeFloat(y) }

    /** Agarra o mouse (mira travada): o cursor do Android para de mexer e de clicar no jogo. */
    fun grabMouse(on: Boolean) = send(InjectServer.OP_GRAB_MOUSE) { writeByte(if (on) 1 else 0) }

    @Volatile private var touchPassOn = false

    /** Repasse do touch (ver InjectServer): ligado junto com os controles do jogo. */
    fun touchPassthrough(on: Boolean) {
        touchPassOn = on
        sendTouchPass()
    }

    private fun sendTouchPass() {
        val dm = context.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val h = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val on = touchPassOn
        send(InjectServer.OP_TOUCH_PASS) { writeByte(if (on) 1 else 0); writeFloat(w); writeFloat(h) }
    }

    fun stop() = io.execute {
        runCatching { out?.apply { writeByte(InjectServer.OP_QUIT.toInt()); flush() } }
        disconnect()
        _status.value = InjectStatus()
    }

    private fun send(op: Byte, body: DataOutputStream.() -> Unit) = io.execute {
        val o = out ?: return@execute
        try {
            o.writeByte(op.toInt())
            o.body()
            o.flush()
        } catch (e: Exception) {
            Log.w(TAG, "conexão com o serviço caiu", e)
            disconnect()
            _status.value = InjectStatus(error = "conexão com o serviço caiu")
        }
    }

    private fun connect(method: ActivationMethod): Boolean {
        if (out != null) return true
        return try {
            val s = LocalSocket().apply { connect(LocalSocketAddress(InjectServer.SOCKET_NAME)) }
            val o = DataOutputStream(s.outputStream.buffered())
            o.writeByte(InjectServer.OP_PING.toInt())
            o.flush()
            s.soTimeout = 2000
            val input = DataInputStream(s.inputStream.buffered())
            if (input.readByte() != InjectServer.MSG_PONG) error("resposta inválida")
            val serverStamp = input.readLong()
            if (serverStamp != installStamp()) {
                // Servidor de uma instalação anterior do app: encerra para subir um novo.
                Log.i(TAG, "servidor de outra versão ($serverStamp); reiniciando")
                o.writeByte(InjectServer.OP_QUIT.toInt())
                o.flush()
                s.close()
                return false
            }
            s.soTimeout = 0
            socket = s
            out = o
            startReader(input, s)
            _status.value = InjectStatus(if (method == ActivationMethod.Shizuku) Backend.Shizuku else Backend.Adb)
            Log.i(TAG, "conectado ao servidor ($method)")
            if (touchPassOn) sendTouchPass()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Recebe o teclado/mouse lido pelo servidor e repassa para quem estiver ouvindo. */
    private fun startReader(input: DataInputStream, s: LocalSocket) = thread(name = "server-input", isDaemon = true) {
        try {
            while (true) {
                when (input.readByte()) {
                    InjectServer.MSG_INPUT -> {
                        input.readByte() // origem (teclado/mouse), não usada aqui
                        val ev = RawInput(input.readShort().toInt() and 0xFFFF, input.readShort().toInt() and 0xFFFF, input.readInt())
                        listeners.forEach { runCatching { it(ev) } }
                    }
                    InjectServer.MSG_FOREGROUND -> {
                        val pkg = input.readUTF()
                        _foregroundPackage.value = pkg
                    }
                    InjectServer.MSG_FPS -> _gameFps.value = input.readInt()
                    InjectServer.MSG_PONG -> Unit
                }
            }
        } catch (_: Exception) {
            io.execute {
                if (socket === s) {
                    disconnect()
                    _status.value = InjectStatus(error = "o serviço foi encerrado")
                }
            }
        }
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    companion object {
        private const val TAG = "TouchInjector"
        private const val LOG_PATH = "/data/local/tmp/klyro-server.log"

        @Volatile
        private var instance: TouchInjector? = null

        fun get(context: Context): TouchInjector =
            instance ?: synchronized(this) {
                instance ?: TouchInjector(context.applicationContext).also { instance = it }
            }
    }
}
