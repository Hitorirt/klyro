package gg.klyro.mapper.adb

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Estado da conexão ADB com o próprio aparelho (depuração sem fio). */
enum class AdbStatus { Disconnected, Connecting, Connected, PairingRequired }

/**
 * Conexão ADB do app. É um singleton do processo,
 * então a tela, o overlay e a notificação de pareamento falam com a mesma conexão.
 */
class AdbRepository private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager by lazy { AdbConnectionManager.get(context) }
    private val shellLock = Mutex()
    private var shell: AdbStream? = null

    private val _status = MutableStateFlow(AdbStatus.Disconnected)
    val status: StateFlow<AdbStatus> = _status.asStateFlow()

    private val _shellOutput = MutableStateFlow("")
    val shellOutput: StateFlow<String> = _shellOutput.asStateFlow()

    /** Tenta conectar sozinho: depuração sem fio já pareada (Android 11+) e, se não der, a porta 5555. */
    fun autoConnect() {
        if (_status.value == AdbStatus.Connected || _status.value == AdbStatus.Connecting) return
        scope.launch {
            _status.value = AdbStatus.Connecting
            _status.value = try {
                var connected = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    connected = try {
                        manager.autoConnect(context, 5_000)
                    } catch (e: AdbPairingRequiredException) {
                        _status.value = AdbStatus.PairingRequired
                        return@launch
                    } catch (t: Throwable) {
                        Log.w(TAG, "autoConnect", t)
                        false
                    }
                }
                if (!connected) connected = runCatching { manager.connect(5555) }.getOrDefault(false)
                if (connected) AdbStatus.Connected else AdbStatus.Disconnected
            } catch (t: Throwable) {
                Log.w(TAG, "autoConnect", t)
                AdbStatus.Disconnected
            }
        }
    }

    fun connect(port: Int) = scope.launch {
        _status.value = AdbStatus.Connecting
        val ok = runCatching { manager.connect(AndroidUtils.getHostIpAddress(context), port) }
            .onFailure { Log.w(TAG, "connect", it) }
            .getOrDefault(false)
        _status.value = if (ok) AdbStatus.Connected else AdbStatus.Disconnected
    }

    fun disconnect() = scope.launch {
        runCatching { shell?.close() }
        shell = null
        runCatching { manager.disconnect() }.onFailure { Log.w(TAG, "disconnect", it) }
        _status.value = AdbStatus.Disconnected
    }

    /** Descobre a porta de pareamento anunciada pela depuração sem fio (espera até 1 minuto). */
    suspend fun findPairingPort(): Int? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
                    if (cont.isActive) cont.resume(port)
                }
                mdns.start()
                cont.invokeOnCancellation { mdns.stop() }
            }
        }
    }

    /** Pareia com o código de 6 dígitos. Só existe no Android 11+. */
    suspend fun pair(port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext false
        runCatching { manager.pair(AndroidUtils.getHostIpAddress(context), port, code) }
            .onFailure { Log.w(TAG, "pair", it) }
            .getOrDefault(false)
    }

    /** Roda um comando no shell ADB (com permissão de shell, sem root). */
    fun execute(command: String) = scope.launch {
        shellLock.withLock {
            try {
                val stream = shell?.takeUnless { it.isClosed } ?: manager.openStream(LocalServices.SHELL).also {
                    shell = it
                    readShell(it)
                }
                if (command == "clear") _shellOutput.value = ""
                stream.openOutputStream().apply {
                    write("$command\n".toByteArray())
                    flush()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "execute", t)
            }
        }
    }

    private fun readShell(stream: AdbStream) = scope.launch {
        runCatching {
            stream.openInputStream().bufferedReader().useLines { lines ->
                lines.forEach { line -> _shellOutput.value = (_shellOutput.value + line + "\n").takeLast(MAX_OUTPUT) }
            }
        }
    }

    companion object {
        private const val TAG = "AdbRepository"
        private const val MAX_OUTPUT = 32_000

        @Volatile
        private var instance: AdbRepository? = null

        fun get(context: Context): AdbRepository =
            instance ?: synchronized(this) {
                instance ?: AdbRepository(context.applicationContext).also { instance = it }
            }
    }
}
