package gg.klyro.mapper.overlay

import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import gg.klyro.mapper.ui.components.KlyroSlider
import androidx.compose.foundation.layout.widthIn
import gg.klyro.mapper.ui.tr
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.klyro.mapper.R
import gg.klyro.mapper.engine.KeyPress
import gg.klyro.mapper.input.InputSummary
import gg.klyro.mapper.input.inputSummary
import gg.klyro.mapper.mapping.GameProfiles
import gg.klyro.mapper.mapping.HudMapping
import gg.klyro.mapper.mapping.InputBinding
import gg.klyro.mapper.mapping.MappingComponent
import gg.klyro.mapper.mapping.MappingRepository
import gg.klyro.mapper.mapping.MappingType
import gg.klyro.mapper.mapping.MouseButton
import gg.klyro.mapper.mapping.Profile
import gg.klyro.mapper.mapping.defaultSizeDp
import gg.klyro.mapper.mapping.label
import gg.klyro.mapper.mapping.normalizeKeyCode
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import kotlin.math.roundToInt

/** Qual atribuição está esperando a próxima tecla: a do controle ou uma direção do analógico. */
private sealed interface Slot {
    data object Main : Slot
    data class Dpad(val dir: Int) : Slot // 0 cima, 1 esquerda, 2 baixo, 3 direita
}

/** Item da paleta: tipo de controle e, para ações prontas, o nome. */
private data class PaletteItem(val type: MappingType, val name: Int, val icon: Int, val action: Int? = null)

private val PALETTE = listOf(
    PaletteItem(MappingType.Button, R.string.type_tap, R.drawable.gi_tap),
    PaletteItem(MappingType.RepeatTap, R.string.type_repeat, R.drawable.gi_repeat),
    PaletteItem(MappingType.Dpad, R.string.type_dpad, R.drawable.gi_analog),
    PaletteItem(MappingType.Aim, R.string.type_aim, R.drawable.gi_aim),
    PaletteItem(MappingType.FireButton, R.string.type_fire, R.drawable.gi_fire),
    PaletteItem(MappingType.MouseUnlock, R.string.type_unlock, R.drawable.gi_unlock),
    PaletteItem(MappingType.Button, R.string.action_jump, R.drawable.gi_jump, R.string.action_jump),
    PaletteItem(MappingType.Button, R.string.action_crouch, R.drawable.gi_crouch, R.string.action_crouch),
    PaletteItem(MappingType.Button, R.string.action_reload, R.drawable.gi_reload, R.string.action_reload),
    PaletteItem(MappingType.Button, R.string.action_interact, R.drawable.gi_interact, R.string.action_interact),
)

/** Teclas rápidas para 1 clique (especialmente úteis em emuladores que interceptam Ctrl/F1/Esc/Alt no Windows). */
private val QUICK_KEYS = listOf(
    "Ctrl" to AndroidKeyEvent.KEYCODE_CTRL_LEFT,
    "Alt" to AndroidKeyEvent.KEYCODE_ALT_LEFT,
    "Shift" to AndroidKeyEvent.KEYCODE_SHIFT_LEFT,
    "SPACE" to AndroidKeyEvent.KEYCODE_SPACE,
    "Tab" to AndroidKeyEvent.KEYCODE_TAB,
    "F1" to AndroidKeyEvent.KEYCODE_F1,
    "F2" to AndroidKeyEvent.KEYCODE_F2,
    "F3" to AndroidKeyEvent.KEYCODE_F3,
    "F4" to AndroidKeyEvent.KEYCODE_F4,
    "Esc" to AndroidKeyEvent.KEYCODE_ESCAPE,
    "`" to AndroidKeyEvent.KEYCODE_GRAVE,
    "Caps" to AndroidKeyEvent.KEYCODE_CAPS_LOCK,
    "1" to AndroidKeyEvent.KEYCODE_1,
    "2" to AndroidKeyEvent.KEYCODE_2,
    "3" to AndroidKeyEvent.KEYCODE_3,
    "4" to AndroidKeyEvent.KEYCODE_4,
    "C" to AndroidKeyEvent.KEYCODE_C,
    "Z" to AndroidKeyEvent.KEYCODE_Z,
    "V" to AndroidKeyEvent.KEYCODE_V,
    "R" to AndroidKeyEvent.KEYCODE_R,
    "E" to AndroidKeyEvent.KEYCODE_E,
    "F" to AndroidKeyEvent.KEYCODE_F,
    "G" to AndroidKeyEvent.KEYCODE_G,
    "Q" to AndroidKeyEvent.KEYCODE_Q,
    "T" to AndroidKeyEvent.KEYCODE_T,
)

private const val PANEL_DP = 282

/**
 * Editor visual estilo GG Mouse Pro / Panda Mouse Pro / KeySync:
 * - Barra superior central flutuante (Sair | Desfocar | Adicionar | Configs | Resetar | Salvar).
 * - Duplo clique em qualquer lugar vazio da tela cria imediatamente um componente "Empty" já ouvindo a tecla.
 * - Linhas guia (mira azul em cruz vertical + horizontal) no componente selecionado + barra de telemetria no topo.
 * - Botão rápido (×) ao lado do componente selecionado para remover em 1 clique.
 * - Segurar e arrastar move o componente sem reabrir o painel lateral se ele estiver oculto; duplo clique no componente abre suas opções.
 * - Gerenciador completo de Configs (Criar nova, Preset FPS/Free Fire, Renomear, Duplicar, Exportar/Copiar código, Compartilhar, Importar/Colar, Salvar arquivo).
 */
@Composable
fun OverlayEditor(
    gamePackage: String?,
    keys: SharedFlow<KeyPress>,
    onAndroidKey: (AndroidKeyEvent) -> Boolean = { false },
    onTyping: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val repo = remember { MappingRepository(context) }
    var profiles by remember { mutableStateOf(repo.loadProfiles(gamePackage)) }
    var mapping by remember { mutableStateOf(profiles.active.mapping) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var slot by remember { mutableStateOf<Slot>(Slot.Main) }
    var notice by remember { mutableStateOf<String?>(null) }
    var ghost by remember { mutableStateOf<Pair<PaletteItem, Offset>?>(null) }
    // Por padrão, começa com a tela limpa estilo GG Mouse Pro (sem a barra lateral tapando a visão);
    // clicar em "Adicionar" ou dar duplo clique num botão abre o painel lateral.
    var paletteHidden by remember { mutableStateOf(true) }
    var showConfigsDialog by remember { mutableStateOf(false) }
    var lastDragDelta by remember { mutableStateOf(Offset.Zero) }
    var listening by remember { mutableStateOf(false) }
    var beforeListen by remember { mutableStateOf<MappingComponent?>(null) }
    val density = LocalDensity.current.density
    val devices by remember { inputSummary(context) }.collectAsStateWithLifecycle(InputSummary())

    val selected = selectedId?.let { mapping[it] }

    /** Mostrar as configurações completas do controle na gaveta (só depois de tocar nele). */
    var panelOpen by remember { mutableStateOf(false) }

    fun select(id: Int?, autoListen: Boolean = true, openPanel: Boolean = true) {
        selectedId = id
        panelOpen = openPanel && id != null
        val comp = id?.let { mapping[it] }
        slot = if (comp?.type == MappingType.Dpad) Slot.Dpad(0) else Slot.Main
        listening = autoListen && id != null && comp?.type != MappingType.Aim
        beforeListen = comp
    }

    fun startListening(s: Slot) {
        slot = s
        beforeListen = selectedId?.let { mapping[it] }
        listening = true
    }

    fun cancelListening() {
        listening = false
        beforeListen = null
    }

    fun commitToProfile(): GameProfiles =
        profiles.copy(profiles = profiles.profiles.map { if (it.id == profiles.activeId) it.copy(mapping = mapping) else it })

    /** Um controle da tela está sendo arrastado (deixa a gaveta transparente). */
    var draggingControl by remember { mutableStateOf(false) }
    val editorPrefs = remember { context.getSharedPreferences("main", android.content.Context.MODE_PRIVATE) }
    /** Gaveta do lado esquerdo (o usuário troca com um toque). */
    var drawerLeft by remember { mutableStateOf(editorPrefs.getBoolean("drawer_left", false)) }
    fun swapDrawerSide() {
        drawerLeft = !drawerLeft
        editorPrefs.edit().putBoolean("drawer_left", drawerLeft).apply()
    }
    var lastAssign by remember { mutableStateOf(0L to InputBinding.NONE) }

    fun assign(binding: InputBinding) {
        val c = selectedId?.let { mapping[it] } ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (lastAssign.second == binding && now - lastAssign.first < 200) return
        lastAssign = now to binding
        val s = if (c.type == MappingType.Dpad && slot == Slot.Main) Slot.Dpad(0) else slot
        mapping = mapping.update(c.id) { cur ->
            when (s) {
                Slot.Main -> cur.copy(binding = binding)
                is Slot.Dpad -> cur.copy(
                    dpad = when (s.dir) {
                        0 -> cur.dpad.copy(up = binding)
                        1 -> cur.dpad.copy(left = binding)
                        2 -> cur.dpad.copy(down = binding)
                        else -> cur.dpad.copy(right = binding)
                    },
                )
            }
        }
        if (s is Slot.Dpad && s.dir < 3) {
            slot = Slot.Dpad(s.dir + 1)
            listening = true
        } else {
            listening = false
            beforeListen = null
        }
    }

    val currentAssign by rememberUpdatedState(::assign)
    val currentSelect by rememberUpdatedState { id: Int? -> select(id) }
    val currentListening by rememberUpdatedState(listening)
    val currentSelectedId by rememberUpdatedState(selectedId)
    val currentMapping by rememberUpdatedState(mapping)

    LaunchedEffect(keys) {
        keys.collect { k ->
            if (k.up) return@collect
            val ctrl = k.meta and AndroidKeyEvent.META_CTRL_ON != 0
            val sel = currentSelectedId?.let { currentMapping[it] }
            when {
                currentListening && sel != null -> {
                    // Clique esquerdo é o clique da tela (no emulador ele chega junto com o toque):
                    // não vira atribuição sozinho. Mouse-L só pelas opções do controle.
                    if (k.mouse == MouseButton.Left) {
                        Unit
                    } else if (k.mouse != MouseButton.None) {
                        currentAssign(InputBinding.mouse(k.mouse))
                    } else if (k.keyCode != AndroidKeyEvent.KEYCODE_UNKNOWN) {
                        currentAssign(InputBinding.key(k.keyCode))
                    }
                }
                ctrl && k.keyCode == AndroidKeyEvent.KEYCODE_D && sel != null -> {
                    mapping = currentMapping.duplicate(sel.id, 16 * density)
                    currentSelect(mapping.components.last().id)
                }
                ctrl && (k.keyCode == AndroidKeyEvent.KEYCODE_FORWARD_DEL || k.keyCode == AndroidKeyEvent.KEYCODE_DEL) && sel != null -> {
                    mapping = currentMapping.remove(sel.id)
                    currentSelect(null)
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { onAndroidKey(it.nativeKeyEvent) }
            .background(Color.Black.copy(alpha = 0.22f)),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val panelPx = PANEL_DP * density

        fun sizePx(c: MappingComponent) = if (c.size > 0) c.size else c.type.defaultSizeDp() * density

        fun addAt(item: PaletteItem, center: Offset, openPanel: Boolean = false) {
            val px = item.type.defaultSizeDp() * density
            val label = item.action?.let { context.getString(it) } ?: ""
            val x = (center.x - px / 2).coerceIn(0f, w - px)
            val y = (center.y - px / 2).coerceIn(0f, h - px)
            mapping = mapping.add(item.type, x = x, y = y, label = label)
            if (openPanel) paletteHidden = false
            select(mapping.components.last().id, autoListen = true, openPanel = openPanel)
        }

        fun resize(c: MappingComponent, factor: Float) {
            mapping = mapping.update(c.id) { it.copy(size = (sizePx(it) * factor).coerceIn(22 * density, minOf(w, h) * 0.9f)) }
        }

        // Fundo interativo:
        // - 1 toque no vazio = desfoca / tira seleção
        // - Duplo clique no vazio = adiciona um botão de Toque ("Empty") exatamente onde clicou e já ouve a tecla!
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(w, h) {
                    detectTapGestures(
                        onTap = { select(null) },
                        onDoubleTap = { pos ->
                            addAt(PALETTE.first(), pos, openPanel = false)
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (!listening || e.type != PointerEventType.Press) continue
                            if (e.changes.none { it.type == PointerType.Mouse }) continue
                            val b = e.buttons
                            val button = when {
                                b.isSecondaryPressed -> MouseButton.Right
                                b.isTertiaryPressed -> MouseButton.Middle
                                else -> null
                            } ?: continue
                            assign(InputBinding.mouse(button))
                            e.changes.forEach { it.consume() }
                        }
                    }
                },
        )

        // Linhas guia em cruz azul neon (estilo GG Mouse Pro) passando pelo centro do controle selecionado
        selected?.let { sel ->
            val cx = sel.x + sizePx(sel) / 2f
            val cy = sel.y + sizePx(sel) / 2f
            Canvas(Modifier.fillMaxSize()) {
                val crossColor = Color(0xFF3B82F6).copy(alpha = 0.85f)
                drawLine(crossColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.6f * density)
                drawLine(crossColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.6f * density)
            }
        }

        // Barra de telemetria estilo GG Mouse Pro (P, X, Y, dX, dY, Prs, Size)
        TelemetryStrip(
            totalCount = mapping.components.size,
            selected = selected,
            lastDelta = lastDragDelta,
            density = density,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp),
        )

        // Controles na tela:
        // - Segurar e arrastar apenas move (NUNCA reabre a aba lateral se ela estiver oculta!)
        // - 1 clique seleciona e já escuta a tecla
        // - Duplo clique no controle abre o painel de opções detalhadas dele
        // - Roda do mouse altera o tamanho
        mapping.components.forEach { c ->
            val isSel = c.id == selectedId
            ControlGlyph(
                c = c,
                selected = isSel,
                listening = isSel && listening,
                onDelete = {
                    mapping = mapping.remove(c.id)
                    if (selectedId == c.id) select(null)
                },
                onSettings = {
                    paletteHidden = true
                    panelOpen = true
                },
                onResizeDrag = { d ->
                    mapping = mapping.update(c.id) { it.copy(size = (sizePx(it) + d * 2f).coerceIn(22 * density, minOf(w, h) * 0.9f)) }
                },
                modifier = Modifier
                    .pointerInput(c.id) {
                        detectDragGestures(
                            onDragStart = {
                                lastDragDelta = Offset.Zero
                                draggingControl = true
                                if (selectedId != c.id) select(c.id, autoListen = false, openPanel = false)
                            },
                            onDragEnd = { draggingControl = false },
                            onDragCancel = { draggingControl = false },
                        ) { change, drag ->
                            change.consume()
                            lastDragDelta = drag
                            mapping = mapping.update(c.id) {
                                it.copy(x = (it.x + drag.x).coerceIn(0f, w - sizePx(it)), y = (it.y + drag.y).coerceIn(0f, h - sizePx(it)))
                            }
                        }
                    }
                    .pointerInput(c.id) {
                        detectTapGestures(
                            onTap = {
                                paletteHidden = true
                                select(c.id, autoListen = true, openPanel = false)
                            },
                            onDoubleTap = {
                                paletteHidden = true
                                select(c.id, autoListen = true, openPanel = true)
                            },
                        )
                    }
                    .pointerInput(c.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == PointerEventType.Scroll) {
                                    val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    mapping[c.id]?.let { resize(it, if (dy < 0) 1.08f else 0.92f) }
                                    e.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
            )
        }

        // Fantasma do item sendo arrastado da paleta.
        ghost?.let { (item, pos) ->
            val sizeDp = item.type.defaultSizeDp()
            val px = sizeDp * density
            Box(
                Modifier
                    .zIndex(3f)
                    .offset { IntOffset((pos.x - px / 2).roundToInt(), (pos.y - px / 2).roundToInt()) }
                    .size(sizeDp.dp)
                    .background(Color(0xFF5C7CFF).copy(alpha = 0.55f), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(item.icon), null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        }

        // Barra superior central estilo GG Mouse Pro: Exit | Unfocus | Add | Configs | Reset | Save
        GgTopToolbar(
            profileName = profiles.active.name,
            addActive = !paletteHidden,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp),
            onExit = onClose,
            onUnfocus = {
                select(null)
                paletteHidden = true
            },
            onAdd = {
                select(null)
                paletteHidden = !paletteHidden
            },
            onConfigs = {
                select(null)
                showConfigsDialog = true
            },
            onReset = {
                mapping = repo.defaultPreset(w, h, density)
                select(null)
                notice = tr("Default FPS layout restored (press Save to keep it)", "Layout padrão FPS restaurado (aperte Salvar para confirmar)")
            },
            onSave = {
                repo.saveProfiles(gamePackage, commitToProfile())
                onClose()
            },
        )

        // Dica estilo GG Mouse Pro na parte inferior central da tela
        if (selected == null && paletteHidden && !showConfigsDialog) {
            Text(
                tr("Double-tap the screen to add a button · Double-tap a button for its settings", "Toque duplo na tela para criar um botão · Toque duplo no botão para abrir configurações"),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall.copy(
                    shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 6f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp, start = 24.dp, end = 24.dp),
            )
        }

        // Avisos (perfil exportado, importado, teclado ausente...).
        val warning = notice ?: if (!devices.hasKeyboard) stringResource(R.string.editor_no_keyboard) else null
        warning?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xF00A0A0D),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color(0xFF24242E)),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    if (notice != null) {
                        Text(
                            "✕",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { notice = null },
                        )
                    }
                }
            }
        }

        // Janelas centralizadas: paleta (botão "+") e opções do controle (engrenagem). Ficam quase
        // transparentes enquanto um controle é arrastado, para ver onde ele vai cair.
        val popupAlpha by animateFloatAsState(if (draggingControl || ghost != null) 0.12f else 1f, label = "popupAlpha")
        val popupMaxH = (h / density * 0.86f).dp
        if (!paletteHidden && !(panelOpen && selected != null)) {
            EditorPopup(
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(1f)
                    .alpha(popupAlpha)
                    .widthIn(max = 440.dp)
                    .heightIn(max = popupMaxH),
            ) {
                Palette(
                    onHide = { paletteHidden = true },
                    drawerLeft = drawerLeft,
                    onSwapSide = { swapDrawerSide() },
                    onTap = { item ->
                        addAt(item, Offset(w / 2, h / 2), openPanel = false)
                        paletteHidden = true
                    },
                    onDragMove = { item, pos -> ghost = item to pos },
                    onDrop = { item, pos ->
                        ghost = null
                        addAt(item, pos, openPanel = false)
                        paletteHidden = true
                    },
                    onDragCancel = { ghost = null },
                    onClearAll = {
                        mapping = HudMapping()
                        select(null)
                    },
                    onLoadPreset = {
                        mapping = repo.defaultPreset(w, h, density)
                        select(null)
                    },
                )
            }
        }
        if (panelOpen && selected != null) {
            EditorPopup(
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(1f)
                    .alpha(popupAlpha)
                    .widthIn(max = 380.dp)
                    .heightIn(max = popupMaxH),
            ) {
                ControlPanel(
                    c = selected,
                    slot = slot,
                    listening = listening,
                    onHide = { panelOpen = false },
                    onListen = { startListening(it) },
                    onCancelListen = { cancelListening() },
                    onQuickKey = { keyCode -> assign(InputBinding.key(keyCode)) },
                    onMouse = { assign(InputBinding.mouse(it)) },
                    onClear = { assign(InputBinding.NONE) },
                    onSize = { dp -> mapping = mapping.update(selected.id) { it.copy(size = dp * density) } },
                    onNudge = { dx, dy ->
                        mapping = mapping.update(selected.id) {
                            it.copy(x = (it.x + dx * density).coerceIn(0f, w - sizePx(it)), y = (it.y + dy * density).coerceIn(0f, h - sizePx(it)))
                        }
                    },
                    onInterval = { ms -> mapping = mapping.update(selected.id) { it.copy(intervalMs = ms) } },
                    onSensitivity = { v -> mapping = mapping.update(selected.id) { it.copy(sensitivity = v) } },
                    onUpdate = { change -> mapping = mapping.update(selected.id, change) },
                    onDuplicate = { mapping = mapping.duplicate(selected.id, 16 * density); select(mapping.components.last().id) },
                    onDelete = { mapping = mapping.remove(selected.id); select(null) },
                    onDone = { select(null) },
                )
            }
        }

        // Modal completo de Gerenciamento de Configs (Criar, Renomear, Duplicar, Exportar, Compartilhar, Importar código, Salvar arquivo)
        if (showConfigsDialog) {
            ConfigManagerModal(
                profiles = commitToProfile(),
                onTyping = onTyping,
                onDismiss = {
                    onTyping(false)
                    showConfigsDialog = false
                },
                onSwitch = { id ->
                    val updated = commitToProfile().copy(activeId = id)
                    profiles = updated
                    mapping = updated.active.mapping
                    repo.saveProfiles(gamePackage, updated)
                    select(null)
                    notice = tr("Config '${updated.active.name}' loaded", "Config '${updated.active.name}' carregada")
                },
                onCreateNew = { name, usePreset ->
                    val initial = if (usePreset) repo.defaultPreset(w, h, density) else HudMapping()
                    val cleanName = name.ifBlank { "Config ${profiles.profiles.size + 1}" }.take(40)
                    val p = Profile(MappingRepository.newId(), cleanName, initial)
                    val updated = commitToProfile().let { it.copy(activeId = p.id, profiles = it.profiles + p) }
                    profiles = updated
                    mapping = p.mapping
                    repo.saveProfiles(gamePackage, updated)
                    select(null)
                    notice = tr("Config '$cleanName' created and saved", "Config '$cleanName' criada e salva")
                },
                onDuplicate = {
                    val base = commitToProfile()
                    val p = base.active.copy(
                        id = MappingRepository.newId(),
                        name = "${base.active.name} (cópia)".take(40),
                    )
                    val updated = base.copy(activeId = p.id, profiles = base.profiles + p)
                    profiles = updated
                    mapping = p.mapping
                    repo.saveProfiles(gamePackage, updated)
                    notice = tr("Config '${p.name}' duplicated", "Config '${p.name}' duplicada")
                },
                onRename = { newName ->
                    val clean = newName.trim().take(40)
                    if (clean.isNotEmpty()) {
                        val updated = commitToProfile().let { gp ->
                            gp.copy(profiles = gp.profiles.map { if (it.id == gp.activeId) it.copy(name = clean) else it })
                        }
                        profiles = updated
                        repo.saveProfiles(gamePackage, updated)
                        notice = tr("Renamed to '$clean'", "Renomeado para '$clean'")
                    }
                },
                onDelete = { id ->
                    val base = commitToProfile()
                    if (base.profiles.size > 1) {
                        val rest = base.profiles.filterNot { it.id == id }
                        val nextActive = if (base.activeId == id) rest.first().id else base.activeId
                        val updated = base.copy(activeId = nextActive, profiles = rest)
                        profiles = updated
                        mapping = updated.active.mapping
                        repo.saveProfiles(gamePackage, updated)
                        select(null)
                        notice = tr("Config removed", "Config removida")
                    }
                },
                onExportClipboard = {
                    val currentProfile = commitToProfile().active.copy(mapping = mapping)
                    val code = repo.export(currentProfile, gamePackage)
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Klyro Config", code))
                    notice = tr(".krylogg code for '${currentProfile.name}' copied!", "Código .krylogg de '${currentProfile.name}' copiado!")
                },
                onShareConfig = {
                    val currentProfile = commitToProfile().active.copy(mapping = mapping)
                    runCatching {
                        repo.shareConfigFile(context, currentProfile, gamePackage)
                        notice = tr("Sharing ${currentProfile.name}.krylogg...", "Compartilhando arquivo ${currentProfile.name}.klyro...")
                    }.onFailure {
                        val code = repo.export(currentProfile, gamePackage)
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Klyro Config", code))
                        notice = tr(".krylogg code copied to share!", "Código .krylogg copiado para compartilhar!")
                    }
                },
                onSaveFile = {
                    val currentProfile = commitToProfile().active.copy(mapping = mapping)
                    runCatching {
                        val file = repo.saveConfigFile(context, currentProfile, gamePackage)
                        val code = repo.export(currentProfile, gamePackage)
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Klyro Config", code))
                        notice = tr("File saved: ${file.name} (code copied too)", "Arquivo salvo: ${file.name} (e código copiado)")
                    }.onFailure {
                        notice = tr("Could not save the file: ${it.message}", "Erro ao salvar arquivo: ${it.message}")
                    }
                },
                onImportText = { rawText ->
                    val text = rawText.ifBlank {
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    }
                    val p = repo.import(text)
                    if (p == null) {
                        notice = tr("Rejected: this is not a Klyro config", "Arquivo/código recusado: isso não é uma config do Klyro")
                        false
                    } else {
                        val updated = commitToProfile().let { it.copy(activeId = p.id, profiles = it.profiles + p) }
                        profiles = updated
                        mapping = p.mapping
                        repo.saveProfiles(gamePackage, updated)
                        notice = tr("Config '${p.name}' (.krylogg) imported!", "Config '${p.name}' (.krylogg) importada com sucesso!")
                        true
                    }
                },
                exportCodeProvider = {
                    repo.export(commitToProfile().active.copy(mapping = mapping), gamePackage)
                },
            )
        }
    }
}

/** Barra de telemetria horizontal estilo GG Mouse Pro: P: i/N | dX | dY | Xv | Yv | Prs: 1.0 | Size */
@Composable
private fun TelemetryStrip(
    totalCount: Int,
    selected: MappingComponent?,
    lastDelta: Offset,
    density: Float,
    modifier: Modifier = Modifier,
) {
    val sizeScale = selected?.let {
        val px = if (it.size > 0) it.size else it.type.defaultSizeDp() * density
        px / (it.type.defaultSizeDp() * density)
    } ?: 1f
    Row(
        modifier
            .height(20.dp)
            .background(Color(0x99CBD5E1)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cellMod = Modifier.weight(1f).padding(horizontal = 6.dp)
        val txtStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text("P: ${if (selected != null) 1 else 0} / $totalCount", style = txtStyle, color = Color(0xFF0F172A), modifier = cellMod)
        Text("dX: %.1f".format(lastDelta.x), style = txtStyle, color = Color(0xFF0F172A), modifier = cellMod)
        Text("dY: %.1f".format(lastDelta.y), style = txtStyle, color = Color(0xFF0F172A), modifier = cellMod)
        Text("Xv: %.1f".format(selected?.x ?: 0f), style = txtStyle, color = Color(0xFF0F172A), modifier = cellMod)
        Text("Yv: %.1f".format(selected?.y ?: 0f), style = txtStyle, color = Color(0xFF0F172A), modifier = cellMod)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xCCEF4444))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Prs: 1.0", style = txtStyle, color = Color.White)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xCCDC2626))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Size: %.1f".format(sizeScale), style = txtStyle, color = Color.White)
        }
    }
}

/** Barra superior centralizada estilo GG Mouse Pro (Exit | Unfocus | Add | Configs | Reset | Save). */
@Composable
private fun GgTopToolbar(
    profileName: String,
    addActive: Boolean,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    onUnfocus: () -> Unit,
    onAdd: () -> Unit,
    onConfigs: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        color = Color(0xEB0A0A0D),
        contentColor = Color.White,
        tonalElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarAction(icon = R.drawable.arrow_back_24px, label = "Exit", onClick = onExit)
            ToolbarAction(icon = R.drawable.gi_unlock, label = "Unfocus", onClick = onUnfocus)
            ToolbarAction(
                icon = R.drawable.add_24px,
                label = "Add",
                highlighted = addActive,
                onClick = onAdd,
            )
            ToolbarAction(
                icon = R.drawable.settings_24px,
                label = "Configs ($profileName)",
                highlighted = false,
                onClick = onConfigs,
            )
            ToolbarAction(icon = R.drawable.gi_reload, label = "Reset", onClick = onReset)
            ToolbarAction(
                icon = R.drawable.check_circle_24px,
                label = "Save",
                highlighted = true,
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun ToolbarAction(
    icon: Int,
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (highlighted) Color(0xFF60A5FA) else Color.White
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Modal completo de Gerenciamento de Configs (Criar, Exportar, Enviar/Compartilhar, Importar/Colar, Duplicar, Renomear, Excluir). */
@Composable
private fun ConfigManagerModal(
    profiles: GameProfiles,
    onTyping: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onCreateNew: (String, Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExportClipboard: () -> Unit,
    onShareConfig: () -> Unit,
    onSaveFile: () -> Unit,
    onImportText: (String) -> Boolean,
    exportCodeProvider: () -> String,
) {
    var newConfigName by remember { mutableStateOf("") }
    var useFpsPreset by remember { mutableStateOf(true) }
    var renameText by remember(profiles.activeId) { mutableStateOf(profiles.active.name) }
    var importCode by remember { mutableStateOf("") }
    var showExportPreview by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onTyping(true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 430.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFA0A0A0D),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0xFF24242E)),
            tonalElevation = 12.dp,
        ) {
            Column(
                Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Cabeçalho
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            tr("Configs", "Gerenciador de Configs & Perfis"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            "Crie, troque, exporte, compartilhe ou importe configurações completas",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(painterResource(R.drawable.close_24px), contentDescription = tr("Close", "Fechar"), tint = Color.White)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // 1. Lista de Configs salvas
                Text(tr("Saved configs (${profiles.profiles.size})", "Suas Configs salvas (${profiles.profiles.size})"), style = MaterialTheme.typography.labelLarge, color = Color.White)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    profiles.profiles.forEach { p ->
                        val isActive = p.id == profiles.activeId
                        Surface(
                            onClick = { onSwitch(p.id) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                            border = BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column {
                                    Text(p.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                    Text(tr("${p.mapping.components.size} buttons", "${p.mapping.components.size} botões"), style = MaterialTheme.typography.labelSmall)
                                }
                                if (profiles.profiles.size > 1) {
                                    Icon(
                                        painterResource(R.drawable.trash_24px),
                                        contentDescription = tr("Delete", "Excluir"),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { onDelete(p.id) },
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Ações da Config Ativa (Renomear, Duplicar, Exportar, Compartilhar, Salvar Arquivo)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Active config: ${profiles.active.name}", "Config ativa: ${profiles.active.name}"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it.take(40) },
                                label = { Text(tr("Config name", "Nome da config")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(onClick = { onRename(renameText) }, shape = RoundedCornerShape(12.dp)) {
                                Text(tr("Rename", "Renomear"))
                            }
                            OutlinedButton(onClick = onDuplicate, shape = RoundedCornerShape(12.dp)) {
                                Text(tr("Duplicate", "Duplicar"))
                            }
                        }

                        // Botões de Exportar / Enviar / Compartilhar / Salvar
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = onExportClipboard,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(tr("Copy code", "Copiar Código"), fontWeight = FontWeight.Bold)
                            }
                            FilledTonalButton(
                                onClick = onShareConfig,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(painterResource(R.drawable.open_in_new_24px), null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Send .krylogg", "Enviar .krylogg"), fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onSaveFile,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(tr("Save .krylogg", "Salvar .krylogg"))
                            }
                        }

                        TextButton(onClick = { showExportPreview = !showExportPreview }) {
                            Text(if (showExportPreview) tr("Hide .krylogg code ▲", "Ocultar código .krylogg ▲") else tr("Show .krylogg code ▼", "Ver código .krylogg ▼"))
                        }
                        if (showExportPreview) {
                            OutlinedTextField(
                                value = exportCodeProvider(),
                                onValueChange = {},
                                readOnly = true,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // 3. Criar Nova Config
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("New config", "Criar Nova Config"), style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newConfigName,
                                onValueChange = { newConfigName = it.take(40) },
                                placeholder = { Text(tr("e.g. Free Fire HUD, Critical Ops...", "Ex.: Free Fire HUD, Critical Ops...")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = useFpsPreset,
                                onClick = { useFpsPreset = !useFpsPreset },
                                label = { Text(if (useFpsPreset) tr("Full FPS preset", "Preset FPS Completo") else tr("Blank", "Em branco")) },
                            )
                            Button(
                                onClick = {
                                    onCreateNew(newConfigName, useFpsPreset)
                                    newConfigName = ""
                                },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(painterResource(R.drawable.add_24px), null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(tr("Create", "Criar"))
                            }
                        }
                    }
                }

                // 4. Importar Config (Colar código JSON)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Import a config", "Importar Config recebida"), style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = importCode,
                                onValueChange = { importCode = it },
                                placeholder = { Text(tr("Paste the code here or import from the clipboard", "Cole o código JSON aqui ou clique em Importar da Área de Transferência")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(
                                onClick = {
                                    if (onImportText(importCode)) importCode = ""
                                },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(tr("Import", "Importar"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Paleta de controles: arrastar até o jogo (ou tocar para pôr no centro). */
@Composable
private fun Palette(
    onHide: () -> Unit,
    drawerLeft: Boolean,
    onSwapSide: () -> Unit,
    onTap: (PaletteItem) -> Unit,
    onDragMove: (PaletteItem, Offset) -> Unit,
    onDrop: (PaletteItem, Offset) -> Unit,
    onDragCancel: () -> Unit,
    onClearAll: () -> Unit,
    onLoadPreset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.palette_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    tr("Double-tap the screen or drag from here", "Toque duplo na tela ou arraste daqui"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            FilledTonalButton(
                onClick = onHide,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text("✕", style = MaterialTheme.typography.labelMedium)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = onLoadPreset,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(tr("FPS preset", "Preset FPS"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(tr("Clear", "Limpar tela"), style = MaterialTheme.typography.labelMedium)
            }
        }

        PALETTE.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    var origin by remember { mutableStateOf(Offset.Zero) }
                    var pos by remember { mutableStateOf(Offset.Zero) }
                    Column(
                        Modifier
                            .weight(1f)
                            .height(82.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                            .onGloballyPositioned { origin = it.positionInRoot() }
                            .pointerInput(item) { detectTapGestures { onTap(item) } }
                            .pointerInput(item) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                        ?: return@awaitEachGesture
                                    pos = origin + first.position
                                    onDragMove(item, pos)
                                    val ended = drag(first.id) { change ->
                                        change.consume()
                                        pos = origin + change.position
                                        onDragMove(item, pos)
                                    }
                                    if (ended) onDrop(item, pos) else onDragCancel()
                                }
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(item.icon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            stringResource(item.name),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPanel(
    c: MappingComponent,
    slot: Slot,
    listening: Boolean,
    onHide: () -> Unit,
    onListen: (Slot) -> Unit,
    onCancelListen: () -> Unit,
    onQuickKey: (Int) -> Unit,
    onMouse: (MouseButton) -> Unit,
    onClear: () -> Unit,
    onSize: (Float) -> Unit,
    onNudge: (Float, Float) -> Unit,
    onInterval: (Int) -> Unit,
    onSensitivity: (Float) -> Unit,
    onUpdate: ((MappingComponent) -> MappingComponent) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val density = LocalDensity.current.density
    Column(
        Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Cabeçalho do controle selecionado
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(typeName(c.type)) + if (c.label.isNotBlank()) " · ${c.label}" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (listening) tr("Waiting for a key…", "Aguardando tecla no teclado…") else tr("Press a key or pick one below", "Aperte uma tecla ou escolha abaixo"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onHide,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text("✕", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Atribuição de tecla
        if (c.type == MappingType.Dpad) {
            Text(stringResource(R.string.panel_dpad_keys), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            val names = listOf(R.string.dir_up, R.string.dir_left, R.string.dir_down, R.string.dir_right)
            c.dpad.all().forEachIndexed { i, b ->
                BindingRow(
                    title = stringResource(names[i]),
                    binding = b,
                    listening = listening && slot == Slot.Dpad(i),
                    onDefine = { onListen(Slot.Dpad(i)) },
                )
            }
            if (listening) {
                TextButton(onClick = onCancelListen, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.panel_cancel_listen))
                }
            }
        } else if (c.type == MappingType.Aim) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                contentColor = Color.White,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    tr("View control (360° aim): place it on a free part of the camera area (right side). While the mouse is locked (Ctrl), moving the mouse turns the camera from this point.", "Controle View (Mira 360°): coloque na área livre da câmera (lado direito da tela). Quando o mouse estiver travado (Ctrl), mexer o mouse gira a visão do jogo a partir deste ponto."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.padding(10.dp),
                )
            }
        } else {
            Text(
                stringResource(
                    when (c.type) {
                        MappingType.MouseUnlock -> R.string.panel_unlock_key
                        else -> R.string.panel_binding
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BindingRow(
                title = stringResource(R.string.panel_key),
                binding = c.binding,
                listening = listening,
                onDefine = { if (listening) onCancelListen() else onListen(Slot.Main) },
            )

            Text(
                tr("Quick keys", "Teclas rápidas (1 toque)"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                QUICK_KEYS.forEach { (label, code) ->
                    val selectedKey = c.binding.mouse == MouseButton.None &&
                        c.binding.keyCode != AndroidKeyEvent.KEYCODE_UNKNOWN &&
                        normalizeKeyCode(c.binding.keyCode) == normalizeKeyCode(code)
                    Surface(
                        onClick = { onQuickKey(code) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (selectedKey) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(
                            1.dp,
                            if (selectedKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // Botões do mouse (para MouseUnlock, não permite Mouse-L para não travar os tiros)
            Text(
                tr("Mouse buttons", "Botões do mouse"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                val allowedMouse = if (c.type == MappingType.MouseUnlock) {
                    listOf(MouseButton.Right to "Mouse-R", MouseButton.Middle to "Mouse-M")
                } else {
                    listOf(MouseButton.Left to "Mouse-L", MouseButton.Right to "Mouse-R", MouseButton.Middle to "Mouse-M")
                }
                allowedMouse.forEach { (b, name) ->
                    val isSel = c.binding.mouse == b
                    FilterChip(
                        selected = isSel,
                        onClick = { onMouse(b) },
                        label = { Text(name, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.panel_clear_binding))
                }
                if (listening) {
                    TextButton(onClick = onCancelListen, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.panel_cancel_listen))
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        val sizeDp = if (c.size > 0) c.size / density else c.type.defaultSizeDp().toFloat()
        Text(stringResource(R.string.panel_size, sizeDp.toInt()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        KlyroSlider(value = sizeDp.coerceIn(22f, 260f), range = 22f..260f, onChange = onSize)

        Text(stringResource(R.string.panel_position), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NudgeButton("←") { onNudge(-1f, 0f) }
            NudgeButton("↑") { onNudge(0f, -1f) }
            NudgeButton("↓") { onNudge(0f, 1f) }
            NudgeButton("→") { onNudge(1f, 0f) }
        }

        when (c.type) {
            MappingType.RepeatTap -> {
                Text(
                    stringResource(R.string.panel_repeat_rate, 1000 / c.intervalMs.coerceAtLeast(1)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                KlyroSlider(value = c.intervalMs.toFloat(), range = 40f..600f, onChange = { onInterval(it.toInt()) })
            }
            MappingType.Aim -> {
                PanelLabel(tr("Horizontal speed (X)", "Velocidade horizontal (X)"), "%.2f".format(c.sensitivity))
                KlyroSlider(value = c.sensitivity, range = 0.2f..4f, onChange = onSensitivity)
                PanelLabel(tr("Vertical speed (Y)", "Velocidade vertical (Y)"), "%.2f".format(c.sensitivityY))
                KlyroSlider(value = c.sensitivityY, range = 0.2f..4f, onChange = { v -> onUpdate { it.copy(sensitivityY = v) } })
                PanelSwitch(
                    tr("Release when the mouse stops", "Soltar quando o mouse parar"),
                    tr("Lifts the finger after 160 ms without movement", "Tira o dedo após 160 ms sem movimento"),
                    c.releaseIdle,
                ) { on -> onUpdate { it.copy(releaseIdle = on) } }
            }
            MappingType.FireButton -> {
                PanelSwitch(
                    tr("Tap continuously while pressed", "Tocar sem parar enquanto segura"),
                    null,
                    c.rapidFire,
                ) { on -> onUpdate { it.copy(rapidFire = on) } }
                if (c.rapidFire) {
                    PanelLabel(tr("Interval", "Intervalo"), "${c.intervalMs} ms")
                    KlyroSlider(value = c.intervalMs.toFloat(), range = 40f..400f, onChange = { onInterval(it.toInt()) })
                }
            }
            MappingType.Dpad -> {
                Text(
                    stringResource(R.string.panel_dpad_reach, (c.sensitivity * 100).toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                KlyroSlider(value = c.sensitivity, range = 0.3f..1f, onChange = onSensitivity)
            }
            else -> Unit
        }

        PanelLabel(tr("Color", "Cor"), null)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BUTTON_COLORS.forEach { argb ->
                val sel = c.color == argb
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (argb == 0L) Color(0xFF1B2333) else Color(argb.toInt()))
                        .border(if (sel) 2.dp else 1.dp, if (sel) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable { onUpdate { it.copy(color = argb) } },
                    contentAlignment = Alignment.Center,
                ) { if (argb == 0L) Text("A", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDuplicate, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.panel_duplicate))
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(painterResource(R.drawable.trash_24px), null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.remove))
            }
        }
        Button(onClick = onHide, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(painterResource(R.drawable.check_24px), null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.panel_done), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BindingRow(title: String, binding: InputBinding, listening: Boolean, onDefine: () -> Unit) {
    Surface(
        onClick = onDefine,
        shape = RoundedCornerShape(14.dp),
        color = if (listening) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            if (listening) 2.dp else 1.dp,
            if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (listening) stringResource(R.string.panel_waiting) else binding.label(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        listening -> MaterialTheme.colorScheme.primary
                        !binding.isSet -> Color(0xFFFF6B6B)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            FilledTonalButton(
                onClick = onDefine,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(if (listening) tr("Listening…", "Ouvindo…") else stringResource(R.string.panel_define_key), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NudgeButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun typeName(t: MappingType) = when (t) {
    MappingType.Button -> R.string.type_tap
    MappingType.RepeatTap -> R.string.type_repeat
    MappingType.Dpad -> R.string.type_dpad
    MappingType.Aim -> R.string.type_aim
    MappingType.FireButton -> R.string.type_fire
    MappingType.MouseUnlock -> R.string.type_unlock
}

/** Cores que o usuário pode dar a um botão (0 = automática pelo tipo). */
private val BUTTON_COLORS = listOf(0L, 0xFF2F7BFF, 0xFF22B8F0, 0xFF34D399, 0xFFFACC15, 0xFFF97316, 0xFFEF4444, 0xFFA855F7, 0xFFFFFFFF)

/** Janela centralizada do editor (paleta e opções do controle). */
@Composable
private fun EditorPopup(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xF20B0E16),
        contentColor = Color.White,
        tonalElevation = 8.dp,
    ) { content() }
}

@Composable
private fun PanelLabel(title: String, value: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PanelSwitch(title: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}
