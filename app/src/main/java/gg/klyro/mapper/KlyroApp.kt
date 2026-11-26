package gg.klyro.mapper

import android.app.Application
import android.content.Context

/**
 * Klyro — mapeador de teclado e mouse para jogos no Android.
 */
class KlyroApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
