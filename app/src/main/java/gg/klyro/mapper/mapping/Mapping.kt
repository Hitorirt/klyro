package gg.klyro.mapper.mapping

import android.view.KeyEvent

/** Tipos de controle que podem ir na tela do jogo. */
enum class MappingType {
    /** Tecla/botão → toque e segura enquanto estiver apertado. */
    Button,

    /** Tecla/botão segurado → toques repetidos no mesmo ponto (a cada [MappingComponent.intervalMs]). */
    RepeatTap,

    /** Quatro teclas (padrão W A S D) → analógico de movimento em 360°. */
    Dpad,

    /** Mouse travado → arrasta na área da câmera (mira). Quem trava/solta é o controle [MouseUnlock]. */
    Aim,

    /** Botão de tiro: por padrão o clique esquerdo do mouse; pode ser qualquer tecla. */
    FireButton,

    /**
     * Tecla que trava/destrava o mouse (estilo BlueStacks). A posição dele na tela é onde o cursor
     * aparece quando o mouse é destravado.
     */
    MouseUnlock,
}

/** Botões do mouse que podem ser atribuídos. */
enum class MouseButton { None, Left, Right, Middle }

/** Normaliza modificadores esquerdo/direito para que Ctrl, Shift e Alt funcionem dos dois lados do teclado. */
fun normalizeKeyCode(code: Int): Int = when (code) {
    KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_CTRL_LEFT
    KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.KEYCODE_SHIFT_LEFT
    KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.KEYCODE_ALT_LEFT
    KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.KEYCODE_META_LEFT
    KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_ENTER
    else -> code
}

/**
 * O que dispara um controle: uma tecla (opcionalmente com Ctrl/Shift/Alt) ou um botão do mouse.
 * [modifiers] usa as máscaras de [KeyEvent.META_CTRL_ON], [KeyEvent.META_SHIFT_ON], [KeyEvent.META_ALT_ON].
 */
data class InputBinding(
    val keyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
    val modifiers: Int = 0,
    val mouse: MouseButton = MouseButton.None,
) {
    val isSet: Boolean get() = keyCode != KeyEvent.KEYCODE_UNKNOWN || mouse != MouseButton.None

    fun matchesKey(code: Int, meta: Int): Boolean =
        mouse == MouseButton.None && keyCode != KeyEvent.KEYCODE_UNKNOWN &&
            normalizeKeyCode(keyCode) == normalizeKeyCode(code) &&
            (modifiers == 0 || isModifierKey(code) || (meta and MODIFIER_MASK) == modifiers)

    companion object {
        val NONE = InputBinding()
        fun key(code: Int, modifiers: Int = 0): InputBinding {
            val norm = normalizeKeyCode(code)
            val cleanMods = if (isModifierKey(norm)) 0 else modifiers and MODIFIER_MASK
            return InputBinding(keyCode = norm, modifiers = cleanMods)
        }
        fun mouse(button: MouseButton) = InputBinding(mouse = button)
        const val MODIFIER_MASK = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON
    }
}

/** Atribuições do analógico (uma por direção). */
data class DpadKeys(
    val up: InputBinding = InputBinding.key(KeyEvent.KEYCODE_W),
    val left: InputBinding = InputBinding.key(KeyEvent.KEYCODE_A),
    val down: InputBinding = InputBinding.key(KeyEvent.KEYCODE_S),
    val right: InputBinding = InputBinding.key(KeyEvent.KEYCODE_D),
) {
    fun all() = listOf(up, left, down, right)
}

/** Diâmetro padrão de cada tipo na tela, em dp (compactos, como no BlueStacks). */
fun MappingType.defaultSizeDp(): Int = when (this) {
    MappingType.Dpad -> 110
    MappingType.Aim -> 140
    else -> 38
}

/**
 * Um controle do mapeamento. [x]/[y] são o canto superior esquerdo em pixels da tela; [size] é o
 * diâmetro em pixels (0 = tamanho padrão do tipo). O toque acontece no centro.
 */
data class MappingComponent(
    val id: Int,
    val type: MappingType,
    val binding: InputBinding = InputBinding.NONE,
    /** Nome da ação (ex.: Pular, Agachar). Só aparece no editor. */
    val label: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val size: Float = 0f,
    /** Toque repetido: intervalo entre toques, em ms. */
    val intervalMs: Int = 80,
    /** Mira: quanto o dedo anda por unidade do mouse. Analógico: quanto do raio o dedo percorre (0.3–1). */
    val sensitivity: Float = 1.2f,
    val dpad: DpadKeys = DpadKeys(),
    /** Mira: sensibilidade do eixo vertical (a [sensitivity] é a horizontal). */
    val sensitivityY: Float = 1.2f,
    /** Mira: solta o dedo quando o mouse fica parado (senão segura até destravar). */
    val releaseIdle: Boolean = true,
    /** Tiro: toca sem parar enquanto segura (usa [intervalMs]). */
    val rapidFire: Boolean = false,
    /** Cor do botão (ARGB); 0 = cor padrão do tipo. */
    val color: Long = 0L,
) {
    /** Controle ainda sem atribuição (mostrado como "aguardando tecla"). */
    val needsBinding: Boolean
        get() = when (type) {
            MappingType.Dpad -> dpad.all().any { !it.isSet }
            MappingType.Aim -> false // a mira não tem tecla: quem trava é o "Destravar mouse"
            else -> !binding.isSet
        }
}

/** Mapeamento completo de um perfil (imutável: cada mudança gera uma cópia). */
data class HudMapping(val components: List<MappingComponent> = emptyList()) {
    private val nextId get() = (components.maxOfOrNull { it.id } ?: 0) + 1

    fun add(type: MappingType, x: Float = 0f, y: Float = 0f, label: String = ""): HudMapping {
        val base = MappingComponent(id = nextId, type = type, x = x, y = y, label = label)
        val c = when (type) {
            MappingType.Dpad -> base.copy(sensitivity = 1f)
            MappingType.Aim -> base.copy(binding = InputBinding.NONE)
            MappingType.MouseUnlock -> base.copy(binding = InputBinding.key(KeyEvent.KEYCODE_CTRL_LEFT))
            MappingType.FireButton -> base.copy(binding = InputBinding.mouse(MouseButton.Left))
            else -> base
        }
        return copy(components = components + c)
    }

    /** Cópia de um controle, um pouco deslocada e sem tecla (para não disparar duas vezes). */
    fun duplicate(id: Int, offsetPx: Float): HudMapping {
        val src = get(id) ?: return this
        val copy = src.copy(id = nextId, x = src.x + offsetPx, y = src.y + offsetPx, binding = InputBinding.NONE)
        return copy(components = components + copy)
    }

    fun remove(id: Int): HudMapping = copy(components = components.filterNot { it.id == id })

    fun update(id: Int, change: (MappingComponent) -> MappingComponent): HudMapping =
        copy(components = components.map { if (it.id == id) change(it) else it })

    operator fun get(id: Int): MappingComponent? = components.firstOrNull { it.id == id }
}
