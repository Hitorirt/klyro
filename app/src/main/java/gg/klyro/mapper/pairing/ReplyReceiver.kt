package gg.klyro.mapper.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import gg.klyro.mapper.adb.AdbRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Recebe o código de 6 dígitos digitado na notificação, acha a porta de pareamento e pareia. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim() ?: return
        val app = context.applicationContext
        if (!CODE.matches(code)) {
            PairingNotifications.showFailed(app)
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                val repo = AdbRepository.get(app)
                val port = repo.findPairingPort()
                val paired = port != null && port > 0 && repo.pair(port, code)
                if (paired) {
                    PairingNotifications.showPaired(app)
                    repo.autoConnect()
                } else {
                    PairingNotifications.showFailed(app)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_REPLY = "key_reply"
        private val CODE = Regex("^\\d{6}$")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
