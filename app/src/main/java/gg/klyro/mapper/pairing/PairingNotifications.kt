package gg.klyro.mapper.pairing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import gg.klyro.mapper.R

/** Notificações do pareamento: pedir o código, pareado e falhou. */
object PairingNotifications {
    private const val ID_ENTER_CODE = 100
    private const val ID_PAIRED = 101
    private const val ID_FAILED = 102
    private const val CHANNEL_PAIRING = "pairing"
    private const val CHANNEL_STATUS = "pairing_status"

    fun showEnterCode(context: Context) {
        createChannels(context)
        val input = RemoteInput.Builder(ReplyReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.pairing_code_label))
            .build()
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, context.getString(R.string.pairing_enter_code), pending,
        ).addRemoteInput(input).build()
        notify(
            context, ID_ENTER_CODE,
            NotificationCompat.Builder(context, CHANNEL_PAIRING)
                .setSmallIcon(R.drawable.ic_stat_klyro)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.pairing_service_found))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .addAction(action),
        )
    }

    fun showPaired(context: Context) = status(context, ID_PAIRED, R.string.pairing_done)

    fun showFailed(context: Context) = status(context, ID_FAILED, R.string.pairing_failed)

    private fun status(context: Context, id: Int, text: Int) {
        createChannels(context)
        NotificationManagerCompat.from(context).cancel(ID_ENTER_CODE)
        notify(
            context, id,
            NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_klyro)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(text))
                .setAutoCancel(true),
        )
    }

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PAIRING, context.getString(R.string.channel_pairing), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_pairing_status), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}
