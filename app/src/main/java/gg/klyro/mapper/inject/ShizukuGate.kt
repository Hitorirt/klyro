package gg.klyro.mapper.inject

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Estado do Shizuku, na ordem do fluxo de ativação. */
enum class ShizukuState { NotInstalled, NotRunning, NoPermission, Ready }

/** Verificações e pedido de autorização do Shizuku (nada de detectar teclado/mouse aqui). */
object ShizukuGate {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4242

    fun state(context: Context): ShizukuState {
        val installed = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
        if (!installed) return ShizukuState.NotInstalled
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return ShizukuState.NotRunning
        if (Shizuku.isPreV11()) return ShizukuState.NoPermission
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) ShizukuState.Ready else ShizukuState.NoPermission
    }

    /** Pede a autorização; [onResult] recebe true se a pessoa permitiu. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure {
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }
}
