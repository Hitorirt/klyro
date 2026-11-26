package gg.klyro.mapper.overlay

import androidx.compose.runtime.collectAsState
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import gg.klyro.mapper.R
import gg.klyro.mapper.engine.InputRouter
import gg.klyro.mapper.engine.MappingEngine
import gg.klyro.mapper.inject.RawInput
import gg.klyro.mapper.inject.TouchInjector
import gg.klyro.mapper.input.InputSummary
import gg.klyro.mapper.input.inputSummary
import gg.klyro.mapper.mapping.ControlsSettings
import gg.klyro.mapper.mapping.GameProfiles
import gg.klyro.mapper.mapping.HudMapping
import gg.klyro.mapper.mapping.MappingRepository
import gg.klyro.mapper.mapping.MappingType
import gg.klyro.mapper.mapping.label
import gg.klyro.mapper.ui.MainActivity
import gg.klyro.mapper.ui.theme.KlyroTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Serviço em primeiro plano do overlay. Nenhuma janela pega o foco do jogo: o teclado e o mouse são
 * lidos pelo servidor shell e chegam aqui pelo [InputRouter]. Janelas, de baixo para cima:
 *  1. Controles desenhados por cima do jogo (sem toque).
 *  2. Painel lateral com as opções.
 *  3. Botão flutuante.
 *  4. Editor (só enquanto edita), também por cima do jogo, que continua aparecendo.
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(gg.klyro.mapper.ui.Lang.wrap(newBase))
    }


    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var wm: WindowManager
    private lateinit var mappings: MappingRepository
    private lateinit var injector: TouchInjector
    private lateinit var engine: MappingEngine
    private lateinit var router: InputRouter
    private val inputListener: (RawInput) -> Unit = { router.onRaw(it) }

    private var controls: ComposeView? = null
    private var bubble: ComposeView? = null
    private var sheet: ComposeView? = null
    private var editor: ComposeView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var sheetParams: WindowManager.LayoutParams
    private var editorParams: WindowManager.LayoutParams? = null
    private var sheetOpen = false
    private var gamePackage: String? = null

    // Estado lido pelas telas em Compose.
    private val mapping = mutableStateOf(HudMapping())
    private val settings = mutableStateOf(ControlsSettings())
    private val controlsOn = mutableStateOf(true)
    private val battery = mutableStateOf(0)
    private val charging = mutableStateOf(false)
    private val wifiBars = mutableStateOf(0)

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level >= 0) battery.value = level * 100 / scale
            val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            charging.value = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
        }
    }

    /** Barras do Wi-Fi (0 a 4) a cada 2 s. */
    private fun startWifiWatch() = lifecycleScope.launch {
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        while (true) {
            wifiBars.value = runCatching {
                @Suppress("DEPRECATION")
                val rssi = wifi.connectionInfo.rssi
                @Suppress("DEPRECATION")
                if (rssi <= -127) 0 else android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5)
            }.getOrDefault(0)
            // Bateria lida direto também (alguns emuladores não mandam o aviso de mudança).
            runCatching {
                val bm = applicationContext.getSystemService(android.os.BatteryManager::class.java)
                val cap = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (cap in 1..100) battery.value = cap
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    private val showControls = mutableStateOf(true)
    private val locked = mutableStateOf(false)
    private val profiles = mutableStateOf<GameProfiles?>(null)
    private val devices = mutableStateOf(InputSummary())
    private val inGame = mutableStateOf(true)

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(
            this, batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startWifiWatch()
        wm = getSystemService(WindowManager::class.java)
        mappings = MappingRepository(this)
        injector = TouchInjector.get(this)
        val dm = resources.displayMetrics
        val screenW = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val screenH = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        engine = MappingEngine(injector, dm.density, screenW, screenH)
        router = InputRouter(engine, injector)
        injector.addInputListener(inputListener)
        lifecycleScope.launch {
            router.locked.collect {
                locked.value = it
                setPointerCapture(it)
            }
        }
        // Teclado/mouse entrando e saindo: mostra aviso sem mexer no perfil salvo.
        lifecycleScope.launch { inputSummary(this@OverlayService).collect { devices.value = it } }
        // Detecta em tempo real se o usuário está dentro do jogo: se sair do jogo (Home, outro app, etc.),
        // esconde automaticamente os controles e destrava o mouse; ao voltar pro jogo, reaparece sozinho!
        lifecycleScope.launch {
            injector.foregroundPackage.collect { fg ->
                updateInGameVisibility(fg)
            }
        }
    }

    private fun updateInGameVisibility(fg: String) {
        val target = gamePackage
        if (fg.isBlank() || target.isNullOrBlank()) return
        // Se a própria janela do nosso overlay (menu lateral ou editor) estiver com foco, continua mostrando
        val visible = fg == target || (fg == packageName && (editor != null || sheetOpen || locked.value))
        if (inGame.value == visible) return
        inGame.value = visible
        router.controlsOn = controlsOn.value && visible
        if (!visible) {
            if (locked.value) router.setLocked(false)
            if (sheetOpen) setSheetOpen(false)
            if (editor != null) closeEditor()
        }
        if (editor == null) {
            val vis = if (visible) View.VISIBLE else View.GONE
            controls?.visibility = vis
            bubble?.visibility = vis
            sheet?.visibility = vis
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        gamePackage = intent?.getStringExtra(EXTRA_GAME) ?: gamePackage
        startInForeground()
        reloadMapping()
        settings.value = mappings.loadSettings()
        engine.settings = settings.value
        injector.start()
        if (bubble == null) show()
        return START_NOT_STICKY
    }

    private fun reloadMapping() {
        val gp = mappings.loadProfiles(gamePackage)
        profiles.value = gp
        mapping.value = gp.active.mapping
        engine.mapping = mapping.value
    }

    private fun switchProfile(id: String) {
        val gp = profiles.value ?: return
        mappings.saveProfiles(gamePackage, gp.copy(activeId = id))
        reloadMapping()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_overlay), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_klyro)
            .setContentTitle(getString(R.string.overlay_running))
            .setContentText(getString(R.string.overlay_running_summary))
            .setContentIntent(open)
            .addAction(0, getString(R.string.overlay_stop), stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun composeView(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@OverlayService)
        setViewTreeSavedStateRegistryOwner(this@OverlayService)
        setContent { KlyroTheme { content() } }
    }

    private fun show() {
        val density = resources.displayMetrics.density
        val bubbleW = (BUBBLE_W_DP * density).roundToInt()
        val bubbleH = (BUBBLE_H_DP * density).roundToInt()
        val sheetWidth = (SHEET_DP * density).roundToInt()

        // 1. Controles desenhados (sem foco e sem toque: o jogo continua com tudo).
        controls = composeView {
            PlayControls(
                mapping = mapping.value,
                opacity = settings.value.opacity,
                show = showControls.value && controlsOn.value,
                locked = locked.value,
                lockLabel = mapping.value.components.firstOrNull {
                    (it.type == MappingType.MouseUnlock || it.type == MappingType.Aim) && it.binding.keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN
                }?.binding?.label() ?: "Ctrl",
                devices = devices.value,
                stats = if (settings.value.showStats) GameStats(
                    fps = injector.gameFps.collectAsState().value,
                    battery = battery.value,
                    charging = charging.value,
                    wifiBars = wifiBars.value,
                    style = settings.value.statsStyle,
                ) else null,
            )
        }.also {
            wm.addView(
                it,
                overlayParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                ).apply { alpha = 0.8f }, // abaixo do limite do Android para não bloquear os toques do jogo
            )
        }

        // 2. Painel lateral.
        sheetParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        ).apply {
            gravity = Gravity.CENTER
        }
        sheet = composeView {
            OverlaySheet(
                gamePackage = gamePackage,
                state = SheetState(
                    controlsOn = controlsOn.value,
                    showControls = showControls.value,
                    locked = locked.value,
                    settings = settings.value,
                    buttonCount = mapping.value.components.size,
                    profiles = profiles.value,
                    devices = devices.value,
                ),
                onProfile = { switchProfile(it) },
                onControlsOn = {
                    controlsOn.value = it
                    router.controlsOn = it && inGame.value
                },
                onShowControls = { showControls.value = it },
                onOpacity = { updateSettings(settings.value.copy(opacity = it)) },
                onShowStats = { updateSettings(settings.value.copy(showStats = it)) },
                onStatsStyle = { updateSettings(settings.value.copy(statsStyle = it)) },
                onSensitivity = { updateSettings(settings.value.copy(mouseSensitivity = it)) },
                onLock = {
                    setSheetOpen(false)
                    router.setLocked(true)
                },
                onStartInjector = { injector.start() },
                onEdit = { openEditor() },
                onClose = { setSheetOpen(false) },
                onStop = { stopSelf() },
            )
        }.also {
            it.visibility = View.GONE
            wm.addView(it, sheetParams)
        }

        // 3. Botão minimalista da estrela + escuta permanente de teclado/mouse (PointerCapture).
        // Quando o mouse está destravado, usa FLAG_NOT_FOCUSABLE para não interferir no jogo;
        // quando o mouse trava (Ctrl), tira o FLAG_NOT_FOCUSABLE e chama requestPointerCapture() diretamente nesta janela
        // (igual ao KeySync), fazendo o cursor do Android sumir e controlando a visão 360° sem travar a tela!
        bubbleParams = overlayParams(bubbleW, bubbleH, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = bubbleY(bubbleH)
        }
        bubble = composeView {
            KlyroBubble(onTap = { setSheetOpen(!sheetOpen) }, onDragY = { dy -> moveBubble(dy) })
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            pointerIcon = android.view.PointerIcon.getSystemIcon(this@OverlayService, android.view.PointerIcon.TYPE_NULL)
            setOnCapturedPointerListener { _, event -> handleCapturedPointer(event) }
            // A captura só é aceita com a janela em foco: pede assim que o foco chega (e de novo se o sistema tirar).
            viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                android.util.Log.i("Klyro", "bubble foco=$hasFocus travado=${locked.value}")
                if (hasFocus && locked.value && !hasPointerCapture()) requestPointerCapture()
            }
            setOnKeyListener { _, _, event -> router.onAndroidKey(event) }
        }.also { wm.addView(it, bubbleParams) }
    }

    private var capture: PointerCaptureView? = null
    private var lastCapturedButtons = 0
    private var lastCapturedAbsX = Float.NaN
    private var lastCapturedAbsY = Float.NaN

    /** Último evento de mouse recebido pela captura (para saber se o aparelho entrega o mouse). */
    private var lastCapturedEventAt = 0L

    private fun handleCapturedPointer(event: android.view.MotionEvent): Boolean {
        lastCapturedEventAt = android.os.SystemClock.uptimeMillis()
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                val relX = event.getAxisValue(android.view.MotionEvent.AXIS_RELATIVE_X)
                val relY = event.getAxisValue(android.view.MotionEvent.AXIS_RELATIVE_Y)
                if (relX != 0f || relY != 0f) {
                    for (h in 0 until event.historySize) {
                        val hx = event.getHistoricalAxisValue(android.view.MotionEvent.AXIS_RELATIVE_X, h)
                        val hy = event.getHistoricalAxisValue(android.view.MotionEvent.AXIS_RELATIVE_Y, h)
                        if (hx != 0f || hy != 0f) router.onCapturedMove(hx, hy)
                    }
                    router.onCapturedMove(relX, relY)
                } else {
                    val x = event.x
                    val y = event.y
                    if (kotlin.math.abs(x) < 350f && kotlin.math.abs(y) < 350f && (x != 0f || y != 0f)) {
                        for (h in 0 until event.historySize) {
                            router.onCapturedMove(event.getHistoricalX(h), event.getHistoricalY(h))
                        }
                        router.onCapturedMove(x, y)
                    } else {
                        val rx = event.rawX
                        val ry = event.rawY
                        if (!lastCapturedAbsX.isNaN() && !lastCapturedAbsY.isNaN()) {
                            val dx = rx - lastCapturedAbsX
                            val dy = ry - lastCapturedAbsY
                            if (dx != 0f || dy != 0f) router.onCapturedMove(dx, dy)
                        }
                        lastCapturedAbsX = rx
                        lastCapturedAbsY = ry
                    }
                }
                syncCapturedButtons(event.buttonState)
            }
            android.view.MotionEvent.ACTION_BUTTON_PRESS -> {
                capturedButton(event.actionButton)?.let { router.onCapturedButton(it, true) }
                syncCapturedButtons(event.buttonState)
            }
            android.view.MotionEvent.ACTION_BUTTON_RELEASE -> {
                capturedButton(event.actionButton)?.let { router.onCapturedButton(it, false) }
                syncCapturedButtons(event.buttonState)
            }
            android.view.MotionEvent.ACTION_DOWN -> {
                if (event.buttonState != 0) syncCapturedButtons(event.buttonState)
                else router.onCapturedButton(gg.klyro.mapper.mapping.MouseButton.Left, true)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (lastCapturedButtons != 0) syncCapturedButtons(0)
                else router.onCapturedButton(gg.klyro.mapper.mapping.MouseButton.Left, false)
            }
        }
        return true
    }

    private fun syncCapturedButtons(state: Int) {
        val changed = state xor lastCapturedButtons
        if (changed == 0) return
        lastCapturedButtons = state
        val checks = listOf(
            android.view.MotionEvent.BUTTON_PRIMARY to gg.klyro.mapper.mapping.MouseButton.Left,
            android.view.MotionEvent.BUTTON_SECONDARY to gg.klyro.mapper.mapping.MouseButton.Right,
            android.view.MotionEvent.BUTTON_TERTIARY to gg.klyro.mapper.mapping.MouseButton.Middle,
        )
        for ((mask, btn) in checks) {
            if (changed and mask != 0) {
                router.onCapturedButton(btn, state and mask != 0)
            }
        }
    }

    private fun capturedButton(b: Int) = when (b) {
        android.view.MotionEvent.BUTTON_PRIMARY -> gg.klyro.mapper.mapping.MouseButton.Left
        android.view.MotionEvent.BUTTON_SECONDARY -> gg.klyro.mapper.mapping.MouseButton.Right
        android.view.MotionEvent.BUTTON_TERTIARY -> gg.klyro.mapper.mapping.MouseButton.Middle
        else -> null
    }

    /**
     * Mouse travado (estilo BlueStacks / Panda Mouse Pro / KeySync):
     * Tira o FLAG_NOT_FOCUSABLE do botão flutuante para ele receber o foco da janela e chama `requestPointerCapture()`,
     * que faz o cursor do mouse do Android sumir e entrega todo o movimento (dx, dy) para girar a câmera 360°.
     */
    /** O aparelho não entrega o mouse para apps (ex.: LDPlayer): travar não pega mais o foco do jogo. */
    private var captureUnsupported = false

    private fun setPointerCapture(on: Boolean) {
        val dm = resources.displayMetrics
        engine.screenWidth = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        engine.screenHeight = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val b = bubble ?: return
        lastCapturedAbsX = Float.NaN
        lastCapturedAbsY = Float.NaN
        lastCapturedButtons = 0
        if (on && captureUnsupported) {
            // Só muda o estado de travado; o foco fica no jogo e as teclas seguem funcionando.
            setSheetOpen(false)
        } else if (on) {
            setSheetOpen(false)
            bubbleParams.flags = (bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            runCatching { wm.updateViewLayout(b, bubbleParams) }
            // Reforço: se o foco demorar, tenta de novo por até ~1,5 s.
            fun retry(left: Int) {
                if (!locked.value || b.hasPointerCapture()) return
                b.requestFocus()
                if (b.hasWindowFocus()) b.requestPointerCapture()
                if (left > 0) b.postDelayed({ retry(left - 1) }, 120)
            }
            b.post { retry(12) }
            // Emuladores como o LDPlayer não repassam o mouse do PC para o Android: com a captura ligada
            // nada chega e o jogo fica sem toque. Se nenhum movimento chegar em 2,5 s, destrava e avisa.
            val lockedAt = android.os.SystemClock.uptimeMillis()
            b.postDelayed({
                if (locked.value && lastCapturedEventAt < lockedAt) {
                    android.util.Log.i("Klyro", "captura sem eventos de mouse: devolvendo o foco ao jogo")
                    captureUnsupported = true
                    // Devolve o foco ao jogo (as teclas voltam na hora) mas continua travado.
                    runCatching { if (b.hasPointerCapture()) b.releasePointerCapture() }
                    bubbleParams.flags = bubbleParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    runCatching { wm.updateViewLayout(b, bubbleParams) }
                    android.widget.Toast.makeText(this, R.string.lock_no_mouse, android.widget.Toast.LENGTH_LONG).show()
                }
            }, 1200)
        } else {
            runCatching { if (b.hasPointerCapture()) b.releasePointerCapture() }
            bubbleParams.flags = bubbleParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { wm.updateViewLayout(b, bubbleParams) }
            engine.unlockPoint()?.let { (x, y) -> injector.cursor(x, y) }
        }
    }

    private fun updateSettings(s: ControlsSettings) {
        settings.value = s
        engine.settings = s
        mappings.saveSettings(s)
    }

    @Volatile private var editorTyping = false

    /**
     * Abre o editor por cima do jogo. Recebe teclas tanto pelo servidor (/dev/input) quanto pelo
     * KeyEvent do Android (importante em emuladores como o BlueStacks, que repassam Ctrl/F1/Esc via janela).
     */
    private fun openEditor() {
        if (editor != null) return
        setSheetOpen(false)
        router.editing = true
        editorTyping = false
        engine.releaseAll()
        // Sem FLAG_NOT_FOCUSABLE: assim o editor também recebe KeyEvents diretos do Android no emulador.
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0)
        editorParams = params
        editor = composeView {
            OverlayEditor(
                gamePackage = gamePackage,
                keys = router.editorKeys,
                onAndroidKey = { ev -> !editorTyping && router.onAndroidKey(ev) },
                onTyping = { typing ->
                    editorTyping = typing
                    setEditorFocusable(true)
                },
                onClose = { closeEditor() },
            )
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, event -> !editorTyping && router.onAndroidKey(event) }
        }.also {
            wm.addView(it, params)
            it.requestFocus()
        }
        controls?.visibility = View.GONE
        bubble?.visibility = View.GONE
    }

    private fun setEditorFocusable(focusable: Boolean) {
        val view = editor ?: return
        val params = editorParams ?: return
        params.flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(view, params)
    }

    private fun closeEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
        editorParams = null
        router.editing = false
        reloadMapping()
        controls?.visibility = View.VISIBLE
        bubble?.visibility = View.VISIBLE
    }

    /**
     * Posição do botão como fração da altura livre. Os jogos rodam deitados, então a altura é o menor lado
     * da tela (antes usava a altura da tela em pé e o botão caía no canto de baixo do jogo).
     * Padrão: perto do topo, um pouco abaixo.
     */
    private var bubbleFrac = 0.18f

    private fun landscapeHeight(): Int = resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }

    private fun bubbleY(h: Int): Int = (bubbleFrac * (landscapeHeight() - h).coerceAtLeast(0)).roundToInt()

    private fun moveBubble(dy: Float) {
        val view = bubble ?: return
        val free = (landscapeHeight() - view.height).coerceAtLeast(1)
        bubbleParams.y = (bubbleParams.y + dy.roundToInt()).coerceIn(0, free)
        bubbleFrac = bubbleParams.y.toFloat() / free
        wm.updateViewLayout(view, bubbleParams)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = bubble ?: return
        bubbleParams.y = bubbleY(view.height)
        runCatching { wm.updateViewLayout(view, bubbleParams) }
    }

    private fun setSheetOpen(open: Boolean) {
        val view = sheet ?: return
        if (sheetOpen == open) return
        sheetOpen = open
        // Abre ao lado do botão flutuante (sem passar da tela).
        view.visibility = if (open) View.VISIBLE else View.GONE
        if (view.isAttachedToWindow) runCatching { wm.updateViewLayout(view, sheetParams) }
    }

    private fun overlayParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or BASE_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        router.controlsOn = false
        router.setLocked(false)
        engine.releaseAll()
        injector.removeInputListener(inputListener)
        setPointerCapture(false)
        listOfNotNull<View>(editor, bubble, sheet, controls).forEach { v -> runCatching { wm.removeView(v) } }
        editor = null
        bubble = null
        sheet = null
        controls = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "gg.klyro.mapper.overlay.STOP"
        private const val EXTRA_GAME = "game"
        private const val BUBBLE_W_DP = 34
        private const val BUBBLE_H_DP = 60 // proporção da arte do mascote espiando (236 x 420)
        private const val SHEET_DP = 320
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        fun intent(context: Context, gamePackage: String?) =
            Intent(context, OverlayService::class.java).putExtra(EXTRA_GAME, gamePackage)
    }
}
