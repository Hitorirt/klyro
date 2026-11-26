package gg.klyro.mapper.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Idioma do app: inglês (padrão) ou português do Brasil, escolhido nas configurações.
 * Os textos em res/values (inglês) e res/values-pt-rBR seguem o idioma escolhido, não o do aparelho.
 */
object Lang {
    const val EN = "en"
    const val PT = "pt-BR"
    private const val KEY = "language"

    /** Idioma atual já aplicado (usado por [tr]). */
    @Volatile var isPt = false
        private set

    fun current(context: Context): String =
        context.getSharedPreferences("main", Context.MODE_PRIVATE).getString(KEY, EN) ?: EN

    fun set(context: Context, code: String) {
        context.getSharedPreferences("main", Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    /** Contexto com o idioma escolhido (usar no attachBaseContext). */
    fun wrap(base: Context): Context {
        isPt = current(base) == PT
        val locale = if (isPt) Locale.forLanguageTag("pt-BR") else Locale.ENGLISH
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

/** Texto escrito no código: devolve a versão no idioma escolhido. */
fun tr(en: String, pt: String): String = if (Lang.isPt) pt else en
