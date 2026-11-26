package gg.klyro.mapper.engine

import gg.klyro.mapper.mapping.Trigger
import android.view.KeyEvent
import gg.klyro.mapper.inject.TouchInjector
import gg.klyro.mapper.mapping.ControlsSettings
import gg.klyro.mapper.mapping.HudMapping
import gg.klyro.mapper.mapping.MappingComponent
import gg.klyro.mapper.mapping.MappingType
import gg.klyro.mapper.mapping.MouseButton
import gg.klyro.mapper.mapping.defaultSizeDp
import gg.klyro.mapper.mapping.normalizeKeyCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Transforma teclado e mouse em toques na tela, seguindo o perfil em uso.
 *
 *  - Botão: apertou = dedo na tela; soltou = tirou o dedo.
 *  - Toque repetido: enquanto segura, toca de novo a cada intervalo.
 *  - Analógico: põe o dedo no centro e arrasta na direção das teclas apertadas (360°, diagonais).
 *  - Mira / Destravar mouse: com o mouse travado (por padrão na tecla Ctrl, estilo Panda Mouse Pro / BlueStacks),
 *    o movimento do mouse arrasta o dedo continuamente numa área ampla de câmera ao redor do ponto da mira;
 *    ao atingir o limite da área, reposiciona no centro para girar 360° sem fim.
 *  - Tiro: igual ao botão; por padrão usa o clique esquerdo.
 */
class MappingEngine(
    private val injector: TouchInjector,
    private val density: Float,
    @Volatile var screenWidth: Float = 1920f,
    @Volatile var screenHeight: Float = 1080f,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var mapping: HudMapping = HudMapping()
        set(value) { releaseAll(); field = value }

    @Volatile var settings: ControlsSettings = ControlsSettings()

    /** Tecla física (normalizada) → controles que ela acionou. */
    private val activeByKey = HashMap<Int, MutableList<Int>>()
    private val activeByMouse = HashMap<MouseButton, MutableList<Int>>()
    private val repeatJobs = HashMap<Int, Job>()
    private val dpadPressed = HashMap<Int, MutableSet<Int>>() // id do analógico → direções (0=cima 1=esq 2=baixo 3=dir)
    private val dpadDown = HashSet<Int>()
    private var aimActive = false
    private var aimPointerId = 2000
    private var aimX = 0f
    private var aimY = 0f
    private var aimIdle: Job? = null

    private fun sizePx(c: MappingComponent) = if (c.size > 0) c.size else c.type.defaultSizeDp() * density
    private fun cx(c: MappingComponent) = c.x + sizePx(c) / 2
    private fun cy(c: MappingComponent) = c.y + sizePx(c) / 2

    private fun pid(c: MappingComponent) = when (c.type) {
        MappingType.Dpad -> 1000 + c.id
        MappingType.Aim, MappingType.MouseUnlock -> 2000 + c.id
        else -> c.id
    }

    /**
     * A tecla trava/solta o mouse?
     * - Verifica qualquer controle [MappingType.MouseUnlock] ou [MappingType.Aim] que tenha tecla configurada.
     * - Se o perfil não tiver outra tecla de trava configurada, aceita **Ctrl** como padrão
     *   (igual ao Panda Mouse Pro / GG Mouse Pro / BlueStacks).
     */
    fun isLockKey(keyCode: Int, meta: Int): Boolean {
        val unlockControls = mapping.components.filter { it.type == MappingType.MouseUnlock && it.binding.mouse == MouseButton.None && it.binding.keyCode != KeyEvent.KEYCODE_UNKNOWN }
        if (unlockControls.isNotEmpty()) {
            return unlockControls.any { it.binding.matchesKey(keyCode, meta) }
        }
        val aimControls = mapping.components.filter { it.type == MappingType.Aim && it.binding.mouse == MouseButton.None && it.binding.keyCode != KeyEvent.KEYCODE_UNKNOWN }
        if (aimControls.isNotEmpty()) {
            return aimControls.any { it.binding.matchesKey(keyCode, meta) }
        }
        // Fallback padrão: Ctrl sempre trava/destrava o mouse se o usuário não escolheu outra tecla.
        return normalizeKeyCode(keyCode) == KeyEvent.KEYCODE_CTRL_LEFT
    }

    /** O botão do mouse trava/solta o mouse? (Clique esquerdo NUNCA trava/destrava o mouse para não interferir no tiro!) */
    fun isLockButton(button: MouseButton): Boolean =
        button != MouseButton.Left && mapping.components.any { it.type == MappingType.MouseUnlock && it.binding.mouse == button }

    /** Onde o cursor deve aparecer ao destravar (centro do controle "Destravar mouse"), ou null. */
    fun unlockPoint(): Pair<Float, Float>? =
        mapping.components.firstOrNull { it.type == MappingType.MouseUnlock }?.let { cx(it) to cy(it) }

    // ---------- Camadas ----------
    private val _layer = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Camada ligada (0 = nenhuma: só os controles "sempre"). */
    val activeLayer: kotlinx.coroutines.flow.StateFlow<Int> = _layer

    private fun visible(c: MappingComponent) = c.layer == 0 || c.layer == _layer.value

    private fun switchLayer(target: Int) {
        releaseAll()
        _layer.value = if (_layer.value == target) 0 else target
    }

    // ---------- Toque, segurar, toque duplo ----------
    private val keyDownAt = HashMap<Int, Long>()
    private val lastTapAt = HashMap<Int, Long>()
    private val holdJobs = HashMap<Int, Job>()
    private val holding = HashSet<Int>()

    /** Toque rápido num botão (40 ms). */
    private fun quickTap(c: MappingComponent) {
        scope.launch {
            injector.down(pid(c), cx(c), cy(c))
            delay(40)
            injector.up(pid(c))
        }
    }

    private fun triggerDown(c: MappingComponent) {
        when (c.trigger) {
            Trigger.Press -> press(c)
            Trigger.Tap -> keyDownAt[c.id] = android.os.SystemClock.uptimeMillis()
            Trigger.Hold -> {
                holdJobs.remove(c.id)?.cancel()
                holdJobs[c.id] = scope.launch {
                    delay(c.holdMs.toLong().coerceIn(100, 2000))
                    synchronized(this@MappingEngine) { holding.add(c.id); press(c) }
                }
            }
            Trigger.DoubleTap -> {
                val now = android.os.SystemClock.uptimeMillis()
                val last = lastTapAt[c.id] ?: 0L
                if (now - last < 250) {
                    lastTapAt.remove(c.id)
                    quickTap(c)
                } else {
                    lastTapAt[c.id] = now
                }
            }
        }
    }

    private fun triggerUp(c: MappingComponent) {
        when (c.trigger) {
            Trigger.Press -> release(c)
            Trigger.Tap -> {
                val at = keyDownAt.remove(c.id) ?: return
                if (android.os.SystemClock.uptimeMillis() - at < c.holdMs) quickTap(c)
            }
            Trigger.Hold -> {
                holdJobs.remove(c.id)?.cancel()
                if (holding.remove(c.id)) release(c)
            }
            Trigger.DoubleTap -> Unit
        }
    }

    // ---------- Rodinha ----------
    private val cycleIndex = HashMap<Int, Int>()

    /** Rodinha do mouse: +1 = para frente (próximo), -1 = para trás (anterior). */
    @Synchronized
    fun onWheel(dir: Int) {
        if (dir == 0) return
        val button = if (dir > 0) MouseButton.WheelUp else MouseButton.WheelDown
        mapping.components.filter { visible(it) && it.binding.mouse == button && it.type in TAP_TYPES }.forEach { quickTap(it) }
        mapping.components.filter { visible(it) && it.type == MappingType.WheelCycle && it.cycleIds.isNotEmpty() }.forEach { cyc ->
            val targets = cyc.cycleIds.mapNotNull { mapping[it] }
            if (targets.isEmpty()) return@forEach
            val cur = cycleIndex[cyc.id]
            val next = if (cur == null) (if (dir > 0) 0 else targets.size - 1) else Math.floorMod(cur + dir, targets.size)
            cycleIndex[cyc.id] = next
            quickTap(targets[next])
        }
    }

    /** Tecla do teclado. Devolve true se a tecla é usada pelo perfil. */
    @Synchronized
    fun onKey(keyCode: Int, meta: Int, down: Boolean, repeat: Boolean): Boolean {
        val norm = normalizeKeyCode(keyCode)
        if (down) {
            if (repeat) return activeByKey.containsKey(norm) || dpadUses(keyCode, meta)
            var used = false
            mapping.components.filter { visible(it) }.forEach { c ->
                when (c.type) {
                    MappingType.Button, MappingType.RepeatTap, MappingType.FireButton ->
                        if (c.binding.matchesKey(keyCode, meta)) {
                            used = true
                            triggerDown(c)
                            activeByKey.getOrPut(norm) { mutableListOf() }.add(c.id)
                        }
                    MappingType.LayerSwitch ->
                        if (c.binding.matchesKey(keyCode, meta)) {
                            switchLayer(c.targetLayer)
                            return true
                        }
                    MappingType.WheelCycle -> Unit
                    MappingType.Dpad -> c.dpad.all().forEachIndexed { dir, b ->
                        if (b.matchesKey(keyCode, meta)) {
                            used = true
                            dpadPressed.getOrPut(c.id) { mutableSetOf() }.add(dir)
                            activeByKey.getOrPut(norm) { mutableListOf() }.add(-(c.id * 10 + dir) - 1)
                            updateDpad(c)
                        }
                    }
                    MappingType.Aim, MappingType.MouseUnlock -> Unit
                }
            }
            return used
        }
        val ids = activeByKey.remove(norm) ?: return false
        ids.forEach { token ->
            if (token >= 0) mapping[token]?.let { triggerUp(it) }
            else {
                val v = -(token + 1)
                val id = v / 10
                val dir = v % 10
                dpadPressed[id]?.remove(dir)
                mapping[id]?.let { updateDpad(it) }
            }
        }
        return true
    }

    private fun dpadUses(keyCode: Int, meta: Int) =
        mapping.components.any { it.type == MappingType.Dpad && it.dpad.all().any { b -> b.matchesKey(keyCode, meta) } }

    /** Botão do mouse (com o mouse travado). */
    @Synchronized
    fun onMouseButton(button: MouseButton, down: Boolean) {
        if (down) {
            val matched = mapping.components.filter { visible(it) && it.binding.mouse == button && it.type in TAP_TYPES }
            val targets = if (matched.isNotEmpty()) matched
            else if (button == MouseButton.Left) mapping.components.filter { it.type == MappingType.FireButton }
            else emptyList()
            targets.forEach { c -> press(c); activeByMouse.getOrPut(button) { mutableListOf() }.add(c.id) }
        } else {
            activeByMouse.remove(button)?.forEach { id -> mapping[id]?.let { release(it) } }
        }
    }

    private fun press(c: MappingComponent) {
        if (c.type == MappingType.RepeatTap || (c.type == MappingType.FireButton && c.rapidFire)) startRepeat(c)
        else injector.down(pid(c), cx(c), cy(c))
    }

    private fun release(c: MappingComponent) {
        repeatJobs.remove(c.id)?.cancel()
        injector.up(pid(c))
    }

    private fun startRepeat(c: MappingComponent) {
        repeatJobs.remove(c.id)?.cancel()
        repeatJobs[c.id] = scope.launch {
            val half = (c.intervalMs.coerceIn(30, 1000) / 2L).coerceAtLeast(15)
            while (isActive) {
                injector.down(pid(c), cx(c), cy(c))
                delay(half)
                injector.up(pid(c))
                delay(half)
            }
        }
    }

    private fun updateDpad(c: MappingComponent) {
        val dirs = dpadPressed[c.id].orEmpty()
        var dx = 0f
        var dy = 0f
        if (0 in dirs) dy -= 1
        if (1 in dirs) dx -= 1
        if (2 in dirs) dy += 1
        if (3 in dirs) dx += 1
        val id = pid(c)
        if (dx == 0f && dy == 0f) {
            if (dpadDown.remove(c.id)) injector.up(id)
            return
        }
        val len = hypot(dx, dy)
        val reach = sizePx(c) / 2 * c.sensitivity.coerceIn(0.3f, 1f)
        if (dpadDown.add(c.id)) injector.down(id, cx(c), cy(c))
        injector.move(id, cx(c) + dx / len * reach, cy(c) + dy / len * reach)
    }

    /**
     * Movimento relativo do mouse travado (em pixels) → controla a câmera do jogo (estilo Panda Mouse Pro / BlueStacks).
     * Funciona se o usuário tiver adicionado "Mira", "Destravar mouse" ou qualquer um dos dois.
     */
    @Synchronized
    fun onMouseMove(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val aim = mapping.components.firstOrNull { it.type == MappingType.Aim }
            ?: mapping.components.firstOrNull { it.type == MappingType.MouseUnlock }
        // Posição central da área de visão 360° (lado direito livre da tela, estilo BlueStacks / Panda Mouse Pro)
        val rawCenterX = aim?.let { cx(it) } ?: (screenWidth * 0.68f)
        val rawCenterY = aim?.let { cy(it) } ?: (screenHeight * 0.45f)
        // Garante que o ponto inicial da câmera não caia em cima da borda nem muito no canto
        val centerX = rawCenterX.coerceIn(screenWidth * 0.35f, screenWidth * 0.85f)
        val centerY = rawCenterY.coerceIn(screenHeight * 0.20f, screenHeight * 0.78f)
        val id = aim?.let { pid(it) } ?: 2000
        val sensX = (aim?.sensitivity ?: 1.2f) * settings.mouseSensitivity
        val sensY = (aim?.sensitivityY ?: 1.2f) * settings.mouseSensitivity

        // Raio amplo de arraste da câmera (mínimo 300dp), independente do ícone "View" ser pequeno na tela!
        val maxRadius = max(aim?.let { sizePx(it) } ?: 0f, 300f * density)
        val margin = 56f * density
        val minX = (screenWidth * 0.28f).coerceAtLeast(margin)
        val maxX = (screenWidth - margin).coerceAtLeast(minX + 200f)
        val minY = margin
        val maxY = (screenHeight - margin).coerceAtLeast(minY + 200f)

        if (!aimActive) {
            aimPointerId = id
            aimX = centerX.coerceIn(minX, maxX)
            aimY = centerY.coerceIn(minY, maxY)
            injector.down(id, aimX, aimY)
            aimActive = true
        }

        val totalDx = dx * sensX
        val totalDy = dy * sensY
        // Divide movimentos muito bruscos em passos suaves para motores Unity/Unreal (Free Fire, Critical Ops).
        val steps = (max(abs(totalDx), abs(totalDy)) / (36f * density)).roundToInt().coerceIn(1, 6)
        val stepX = totalDx / steps
        val stepY = totalDy / steps

        repeat(steps) {
            aimX += stepX
            aimY += stepY
            // Sempre move o dedo primeiro para que a câmera gire de verdade!
            injector.move(id, aimX.coerceIn(minX, maxX), aimY.coerceIn(minY, maxY))

            // Se chegou na borda da área de câmera ou da tela, solta e recomeça do centro para girar 360° infinitamente.
            val outOfBounds = hypot(aimX - centerX, aimY - centerY) > maxRadius ||
                aimX <= minX || aimX >= maxX || aimY <= minY || aimY >= maxY
            if (outOfBounds) {
                injector.up(id)
                aimX = centerX.coerceIn(minX, maxX)
                aimY = centerY.coerceIn(minY, maxY)
                injector.down(id, aimX, aimY)
            }
        }

        aimIdle?.cancel()
        if (aim?.releaseIdle != false) {
            aimIdle = scope.launch {
                delay(AIM_IDLE_MS)
                releaseAim()
            }
        }
    }

    @Synchronized
    private fun releaseAim() {
        if (!aimActive) return
        injector.up(aimPointerId)
        aimActive = false
    }

    /** Solta tudo (ao travar/destravar, perder o foco ou trocar de perfil). */
    @Synchronized
    fun releaseAll() {
        repeatJobs.values.forEach { it.cancel() }
        repeatJobs.clear()
        holdJobs.values.forEach { it.cancel() }
        holdJobs.clear()
        holding.clear()
        keyDownAt.clear()
        activeByKey.clear()
        activeByMouse.clear()
        dpadPressed.clear()
        dpadDown.clear()
        if (aimActive) injector.up(aimPointerId)
        aimActive = false
        aimIdle?.cancel()
        injector.releaseAll()
    }

    /** Há algum controle que usa botões do mouse? */
    fun usesMouseButtons(): Boolean = mapping.components.any { it.binding.mouse != MouseButton.None }

    private companion object {
        const val AIM_IDLE_MS = 650L
        val TAP_TYPES = setOf(MappingType.Button, MappingType.RepeatTap, MappingType.FireButton)
    }
}
