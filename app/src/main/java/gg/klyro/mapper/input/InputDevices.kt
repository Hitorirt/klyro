package gg.klyro.mapper.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Um teclado ou mouse que o Android reconheceu como dispositivo de entrada. */
data class ConnectedDevice(
    val id: Int,
    val name: String,
    val isKeyboard: Boolean,
    val isMouse: Boolean,
    val isExternal: Boolean,
) {
    /** Nome limpo e minimalista para exibir na UI (sem nomes internos do kernel/emulador como VirtualMouse ou AT Translated). */
    val cleanName: String
        get() = when {
            name.contains("VirtualMouse", ignoreCase = true) -> "Mouse Gamer"
            name.contains("ImExPS/2", ignoreCase = true) -> "Mouse USB / PC"
            name.contains("AT Translated", ignoreCase = true) -> "Teclado Padrão"
            name.equals("input", ignoreCase = true) -> if (isMouse) "Mouse" else "Teclado"
            else -> name
        }
}

/** Resumo do que está conectado agora. */
data class InputSummary(val devices: List<ConnectedDevice> = emptyList()) {
    val keyboards get() = devices.filter { it.isKeyboard }
    val mice get() = devices.filter { it.isMouse }
    val hasKeyboard get() = keyboards.isNotEmpty()
    val hasMouse get() = mice.isNotEmpty()
}

/**
 * Teclados e mouses reconhecidos pelo Android (USB/OTG, Bluetooth ou do emulador), atualizado sempre
 * que um dispositivo entra ou sai. Usa só as APIs de entrada do Android: um cabo conectado não conta,
 * só conta o que o sistema registrou como teclado alfabético ou mouse.
 */
fun inputSummary(context: Context): Flow<InputSummary> = callbackFlow<InputSummary> {
    val im = context.getSystemService(InputManager::class.java)
    fun scan(): InputSummary {
        val list = im.inputDeviceIds.toList().mapNotNull { id: Int ->
            val d = im.getInputDevice(id) ?: return@mapNotNull null
            if (d.isVirtual) return@mapNotNull null
            val keyboard = d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            val mouse = d.supportsSource(InputDevice.SOURCE_MOUSE) ||
                d.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                d.name.contains("Mouse", ignoreCase = true)
            if (!keyboard && !mouse) return@mapNotNull null
            ConnectedDevice(id, d.name, keyboard, mouse, d.isExternal)
        }.toMutableList()
        // Quando o PointerCapture está ativo (ex.: LDPlayer / BlueStacks), o InputReader reconfigura o mouse como
        // "VirtualMouse" e remove temporariamente o SOURCE_MOUSE da lista pública do InputManager.
        if (list.none { it.isMouse }) {
            val hasMouseInProc = runCatching {
                java.io.File("/proc/bus/input/devices").readText().contains("mouse", ignoreCase = true)
            }.getOrDefault(false)
            if (hasMouseInProc || list.any { it.isKeyboard }) {
                list.add(ConnectedDevice(-99, "Mouse", isKeyboard = false, isMouse = true, isExternal = true))
            }
        }
        return InputSummary(list)
    }
    val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { trySend(scan()) }
        override fun onInputDeviceRemoved(deviceId: Int) { trySend(scan()) }
        override fun onInputDeviceChanged(deviceId: Int) { trySend(scan()) }
    }
    trySend(scan())
    im.registerInputDeviceListener(listener, null)
    awaitClose { im.unregisterInputDeviceListener(listener) }
}.distinctUntilChanged()
