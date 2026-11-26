package gg.klyro.mapper.data

import android.content.Context

/** Como o app é ativado: Shizuku (principal) ou Depuração sem fio (alternativa). */
enum class ActivationMethod { Shizuku, WirelessDebugging }

/** Preferências gerais do app. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("main", Context.MODE_PRIVATE)

    var activationMethod: ActivationMethod
        get() = ActivationMethod.entries.getOrElse(prefs.getInt(KEY_ACTIVATION, 0)) { ActivationMethod.Shizuku }
        set(value) = prefs.edit().putInt(KEY_ACTIVATION, value.ordinal).apply()

    private companion object {
        const val KEY_ACTIVATION = "activation_method"
    }
}
