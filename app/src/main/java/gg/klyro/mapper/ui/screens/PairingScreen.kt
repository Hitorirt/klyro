package gg.klyro.mapper.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.klyro.mapper.R
import gg.klyro.mapper.ui.theme.KlyroColors
import gg.klyro.mapper.ui.components.KlyroTopBar
import gg.klyro.mapper.ui.components.KlyroStep
import gg.klyro.mapper.ui.components.KlyroDivider
import gg.klyro.mapper.ui.components.KlyroCard
import gg.klyro.mapper.ui.components.KlyroButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import gg.klyro.mapper.adb.AdbPairingDetector
import gg.klyro.mapper.adb.AdbRepository
import gg.klyro.mapper.adb.AdbStatus
import gg.klyro.mapper.pairing.PairingNotifications

private fun notificationsAllowed(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    else NotificationManagerCompat.from(context).areNotificationsEnabled()

@Composable
fun PairingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val adb = remember { AdbRepository.get(context) }
    val status by adb.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var notificationsOk by remember { mutableStateOf(notificationsAllowed(context)) }
    var detectorBound by remember { mutableStateOf(false) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOk = notificationsAllowed(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsOk = notificationsAllowed(context) }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                (service as? AdbPairingDetector.LocalBinder)?.service?.onPairingDetected = { PairingNotifications.showEnterCode(context.applicationContext) }
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (detectorBound) runCatching { context.unbindService(connection) }
        }
    }

    val activated = stringResource(R.string.activated)
    LaunchedEffect(status) { if (status == AdbStatus.Connected) snackbar.showSnackbar(activated) }

    Box(
        Modifier
            .fillMaxSize()
            .background(KlyroColors.Background),
    ) {
        Column(Modifier.fillMaxSize()) {
            KlyroTopBar(stringResource(R.string.pairing_title), onBack)
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!notificationsOk) {
                    KlyroCard {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(KlyroColors.Red))
                            Text(
                                stringResource(R.string.pairing_notifications_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            KlyroButton(stringResource(R.string.pairing_allow_notifications), primary = true, onClick = {
                                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
                            })
                        }
                    }
                }

                KlyroCard {
                    KlyroStep(1, stringResource(R.string.pairing_step1), done = false, note = stringResource(R.string.pairing_step1_note)) {
                        KlyroButton(stringResource(R.string.pairing_open_developer), onClick = {
                            val intent = Intent(context, AdbPairingDetector::class.java)
                            context.startService(intent)
                            detectorBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                            runCatching {
                                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                context.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        })
                    }
                    KlyroDivider()
                    KlyroStep(2, stringResource(R.string.pairing_step2), done = false)
                    KlyroDivider()
                    val connected = status == AdbStatus.Connected
                    KlyroStep(3, stringResource(R.string.pairing_step3), done = connected, note = if (connected) null else stringResource(R.string.pairing_step3_note)) {
                        if (!connected) {
                            KlyroButton(stringResource(R.string.pairing_activate), primary = true, enabled = status != AdbStatus.Connecting, onClick = { adb.autoConnect() })
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
