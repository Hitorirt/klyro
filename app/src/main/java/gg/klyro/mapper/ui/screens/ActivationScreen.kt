package gg.klyro.mapper.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.klyro.mapper.R
import gg.klyro.mapper.adb.AdbRepository
import gg.klyro.mapper.adb.AdbStatus
import gg.klyro.mapper.data.ActivationMethod
import gg.klyro.mapper.data.Settings
import gg.klyro.mapper.inject.Backend
import gg.klyro.mapper.inject.ShizukuGate
import gg.klyro.mapper.inject.ShizukuState
import gg.klyro.mapper.inject.TouchInjector
import gg.klyro.mapper.ui.components.KlyroButton
import gg.klyro.mapper.ui.components.KlyroCard
import gg.klyro.mapper.ui.components.KlyroDivider
import gg.klyro.mapper.ui.components.KlyroSegmented
import gg.klyro.mapper.ui.components.KlyroStep
import gg.klyro.mapper.ui.components.KlyroTopBar
import gg.klyro.mapper.ui.theme.KlyroColors
import gg.klyro.mapper.ui.Lang
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TestResult { Idle, Running, Ok, Failed }

/** O Android não está "otimizando" (pausando) o Klyro em segundo plano. */
private fun ignoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")
private fun askIgnoreBattery(context: Context) {
    val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(direct) }
        .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

@Composable
fun ActivationScreen(onBack: () -> Unit, onOpenPairing: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val injector = remember { TouchInjector.get(context) }
    val adb = remember { AdbRepository.get(context) }
    val status by injector.status.collectAsStateWithLifecycle()
    val adbStatus by adb.status.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(settings.activationMethod.ordinal) }
    var shizuku by remember { mutableStateOf(ShizukuGate.state(context)) }
    var battery by remember { mutableStateOf(ignoringBattery(context)) }
    var test by remember { mutableStateOf(TestResult.Idle) }
    var testTarget by remember { mutableStateOf<Offset?>(null) }
    var testHit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rootView = LocalView.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        shizuku = ShizukuGate.state(context)
        battery = ignoringBattery(context)
    }
    LaunchedEffect(tab) { shizuku = ShizukuGate.state(context) }

    fun startWith(method: ActivationMethod) {
        settings.activationMethod = method
        test = TestResult.Idle
        injector.restart()
    }

    fun runTest() {
        val target = testTarget ?: return
        test = TestResult.Running
        testHit = false
        scope.launch {
            val loc = IntArray(2).also { rootView.getLocationOnScreen(it) }
            injector.down(TEST_POINTER, target.x + loc[0], target.y + loc[1])
            delay(60)
            injector.up(TEST_POINTER)
            repeat(20) {
                if (testHit) {
                    test = TestResult.Ok
                    return@launch
                }
                delay(100)
            }
            test = TestResult.Failed
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(KlyroColors.Background),
    ) {
        KlyroTopBar(stringResource(R.string.activation_title), onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status + teste de toque real
            KlyroCard {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (status.ready) KlyroColors.Green else KlyroColors.Red),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                when (status.backend) {
                                    Backend.Shizuku -> R.string.act_status_shizuku
                                    Backend.Adb -> R.string.act_status_adb
                                    Backend.None -> R.string.act_status_off
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        status.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = KlyroColors.TextMuted)
                        }
                    }
                }
                KlyroDivider()
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when (test) {
                                    TestResult.Ok -> KlyroColors.Green.copy(alpha = 0.14f)
                                    TestResult.Failed -> KlyroColors.Red.copy(alpha = 0.14f)
                                    else -> KlyroColors.CardElevated
                                },
                            )
                            .border(1.dp, KlyroColors.Border, RoundedCornerShape(10.dp))
                            .onGloballyPositioned { testTarget = it.boundsInWindow().center }
                            .pointerInput(Unit) { detectTapGestures { testHit = true } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(
                                when (test) {
                                    TestResult.Idle -> R.string.act_test_area
                                    TestResult.Running -> R.string.act_test_running
                                    TestResult.Ok -> R.string.act_test_ok
                                    TestResult.Failed -> R.string.act_test_failed
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (test) {
                                TestResult.Ok -> KlyroColors.Green
                                TestResult.Failed -> KlyroColors.Red
                                else -> KlyroColors.TextMuted
                            },
                        )
                    }
                    KlyroButton(
                        stringResource(R.string.act_test),
                        onClick = { runTest() },
                        primary = true,
                        enabled = status.ready && test != TestResult.Running,
                    )
                }
            }

            KlyroSegmented(
                listOf(stringResource(R.string.method_shizuku), stringResource(R.string.method_wireless)),
                selected = tab,
                onSelect = { tab = it },
            )

            KlyroCard {
                when (tab) {
                    0 -> {
                        KlyroStep(1, stringResource(R.string.act_shizuku_installed), done = shizuku != ShizukuState.NotInstalled) {
                            if (shizuku == ShizukuState.NotInstalled) {
                                KlyroButton(stringResource(R.string.act_shizuku_get), onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                })
                            }
                        }
                        KlyroDivider()
                        val running = shizuku == ShizukuState.NoPermission || shizuku == ShizukuState.Ready
                        KlyroStep(2, stringResource(R.string.act_shizuku_running), done = running, note = if (running) null else stringResource(R.string.act_shizuku_running_note)) {
                            if (!running) {
                                KlyroButton(stringResource(R.string.act_shizuku_open), enabled = shizuku != ShizukuState.NotInstalled, onClick = {
                                    context.packageManager.getLaunchIntentForPackage(ShizukuGate.PACKAGE)
                                        ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                })
                            }
                        }
                        KlyroDivider()
                        KlyroStep(3, stringResource(R.string.act_shizuku_permission), done = shizuku == ShizukuState.Ready) {
                            if (shizuku != ShizukuState.Ready) {
                                KlyroButton(stringResource(R.string.act_shizuku_authorize), enabled = shizuku == ShizukuState.NoPermission, onClick = {
                                    ShizukuGate.requestPermission { shizuku = ShizukuGate.state(context) }
                                })
                            }
                        }
                        KlyroDivider()
                        KlyroStep(4, stringResource(R.string.act_start_service), done = status.backend == Backend.Shizuku) {
                            KlyroButton(stringResource(R.string.act_start), primary = true, enabled = shizuku == ShizukuState.Ready, onClick = {
                                startWith(ActivationMethod.Shizuku)
                            })
                        }
                    }
                    else -> {
                        KlyroStep(1, stringResource(R.string.act_wireless_pair), done = adbStatus == AdbStatus.Connected, note = stringResource(R.string.act_wireless_note)) {
                            KlyroButton(stringResource(R.string.act_wireless_open), onClick = onOpenPairing)
                        }
                        KlyroDivider()
                        KlyroStep(2, stringResource(R.string.act_start_service), done = status.backend == Backend.Adb && settings.activationMethod == ActivationMethod.WirelessDebugging) {
                            KlyroButton(stringResource(R.string.act_start), primary = true, enabled = adbStatus == AdbStatus.Connected, onClick = {
                                startWith(ActivationMethod.WirelessDebugging)
                            })
                        }
                    }
                }
            }

            // Permissões do sistema
            Text(
                stringResource(R.string.perm_title),
                style = MaterialTheme.typography.labelLarge,
                color = KlyroColors.TextMuted,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            KlyroCard {
                KlyroStep(1, stringResource(R.string.perm_background), done = battery, note = stringResource(R.string.perm_background_note)) {
                    if (!battery) KlyroButton(stringResource(R.string.perm_allow), onClick = { askIgnoreBattery(context) })
                }
            }

            // Idioma do app (inglês é o padrão)
            Text(
                stringResource(R.string.lang_title),
                style = MaterialTheme.typography.labelLarge,
                color = KlyroColors.TextMuted,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            KlyroSegmented(
                listOf("English", "Português"),
                selected = if (Lang.current(context) == Lang.PT) 1 else 0,
                onSelect = { i ->
                    val code = if (i == 1) Lang.PT else Lang.EN
                    if (code != Lang.current(context)) {
                        Lang.set(context, code)
                        (context as? android.app.Activity)?.recreate()
                    }
                },
            )
        }
    }
}

private const val TEST_POINTER = 9001
