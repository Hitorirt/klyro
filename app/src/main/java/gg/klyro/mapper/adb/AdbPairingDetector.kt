package gg.klyro.mapper.adb

import android.app.Service
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.IBinder
import gg.klyro.mapper.pairing.PairingNotifications

/**
 * Fica escutando o anúncio "Parear dispositivo com código" da depuração sem fio (mDNS).
 * Quando aparece, mostra a notificação onde a pessoa digita o código sem sair das configurações.
 */
class AdbPairingDetector : Service() {

    inner class LocalBinder : Binder() {
        val service: AdbPairingDetector get() = this@AdbPairingDetector
    }

    private val binder = LocalBinder()
    private lateinit var nsd: NsdManager
    private var listening = false
    var onPairingDetected: (() -> Unit)? = null

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { listening = false }
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        override fun onDiscoveryStarted(serviceType: String?) { listening = true }
        override fun onDiscoveryStopped(serviceType: String?) { listening = false }
        override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            if (serviceInfo?.serviceType?.contains("adb-tls-pairing") == true) {
                onPairingDetected?.invoke() ?: PairingNotifications.showEnterCode(applicationContext)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        nsd = getSystemService(NsdManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!listening) nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (listening) runCatching { nsd.stopServiceDiscovery(discovery) }
        super.onDestroy()
    }

    private companion object {
        const val SERVICE_TYPE = "_adb-tls-pairing._tcp."
    }
}
