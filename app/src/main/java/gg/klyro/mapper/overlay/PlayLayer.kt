package gg.klyro.mapper.overlay

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import gg.klyro.mapper.mapping.MouseButton
import gg.klyro.mapper.mapping.InputBinding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.klyro.mapper.R
import gg.klyro.mapper.input.InputSummary
import gg.klyro.mapper.mapping.HudMapping
import gg.klyro.mapper.mapping.MappingComponent
import gg.klyro.mapper.mapping.MappingType
import gg.klyro.mapper.mapping.defaultSizeDp
import gg.klyro.mapper.mapping.label
import kotlin.math.roundToInt

/** Desenho dos controles durante a partida (só visual), com a opacidade escolhida. */
@Composable
fun PlayControls(mapping: HudMapping, opacity: Float, show: Boolean, locked: Boolean, lockLabel: String?, devices: InputSummary, stats: GameStats? = null, layer: Int = 0) {
    Box(Modifier.fillMaxSize()) {
        if (stats != null) StatsLayer(stats)
        val missing = when {
            mapping.components.isEmpty() -> null
            !devices.hasKeyboard -> stringResource(R.string.play_keyboard_missing)
            else -> null
        }
        missing?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(Color(0xD9B3261E), RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (show) {
            if (opacity > 0f) mapping.components.filter { it.layer == 0 || it.layer == layer }.forEach { c -> ControlGlyph(c, Modifier.alpha(opacity.coerceIn(0f, 1f))) }
        }
        if (locked) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .background(Color(0xCC0B101D), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF5C7CFF).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.gi_unlock),
                    contentDescription = null,
                    tint = Color(0xFF6EA8FF),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (lockLabel != null) stringResource(R.string.play_mouse_locked, lockLabel) else stringResource(R.string.play_mouse_locked_esc),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Diâmetro do controle em dp. */
@Composable
fun controlSizeDp(c: MappingComponent): Float =
    if (c.size > 0) c.size / LocalDensity.current.density else c.type.defaultSizeDp().toFloat()

private val Waiting = Color(0xFFFF6B6B)
private val NeonBlue = Color(0xFF5C7CFF)
private val NeonCyan = Color(0xFF38BDF8)

/**
 * Um controle desenhado na tela (partida e editor) — estilo GG Mouse Pro / KeySync:
 * anel azul-violeta neon ou branco, fundo translúcido escuro, botão "View" preenchido em azul-violeta
 * para a Mira, bússola WASD com setas azuis, e badge (×) de remoção rápida quando selecionado no editor.
 */
@Composable
fun ControlGlyph(
    c: MappingComponent,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    listening: Boolean = false,
    onResizeDrag: ((Float) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val custom = if (c.color != 0L) Color(c.color.toInt()) else null
    val sizeDp = controlSizeDp(c)
    val waiting = c.needsBinding
    val ring = when {
        listening -> NeonCyan
        custom != null && !selected -> custom
        selected -> Color.White
        waiting -> Color.White.copy(alpha = 0.9f)
        c.type == MappingType.Aim -> NeonBlue
        c.type == MappingType.MouseUnlock -> NeonCyan
        c.type == MappingType.FireButton -> NeonBlue
        else -> NeonBlue.copy(alpha = 0.92f)
    }
    val fill = if (custom != null) custom.copy(alpha = 0.35f) else when (c.type) {
        MappingType.Aim -> Color(0xCC5C7CFF)
        MappingType.MouseUnlock -> Color(0xCC2697FF)
        MappingType.FireButton -> Color(0x88141A2E)
        MappingType.Dpad -> Color(0x380B101D)
        MappingType.RepeatTap -> Color(0x88152955)
        MappingType.Button -> Color(0x780B101D)
        MappingType.LayerSwitch -> Color(0x88302060)
        MappingType.WheelCycle -> Color(0x88203040)
    }
    val textSize = (sizeDp * 0.25f).coerceIn(8.5f, 13.5f).sp
    Box(
        Modifier
            .offset { IntOffset(c.x.roundToInt(), c.y.roundToInt()) }
            .then(modifier)
            .size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(fill, CircleShape)
                .border(if (selected || listening) 2.2.dp else 1.6.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (c.type) {
                MappingType.Dpad -> {
                    val keySize = (sizeDp * 0.3f).dp
                    val small = (sizeDp * 0.11f).coerceIn(9f, 14f).sp
                    val inset = (sizeDp * 0.05f).dp
                    listOf(
                        Alignment.TopCenter to c.dpad.up,
                        Alignment.CenterStart to c.dpad.left,
                        Alignment.BottomCenter to c.dpad.down,
                        Alignment.CenterEnd to c.dpad.right,
                    ).forEach { (at, b) ->
                        Box(
                            Modifier
                                .align(at)
                                .padding(inset)
                                .size(keySize)
                                .background(Color(0xB30E1320), CircleShape)
                                .border(1.4.dp, if (b.isSet) Color.White.copy(alpha = 0.9f) else Waiting, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(faceText(b), color = if (b.isSet) Color.White else Waiting, fontSize = small, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    Box(Modifier.size((sizeDp * 0.1f).dp).background(ring.copy(alpha = 0.6f), CircleShape))
                }
                MappingType.Aim -> {
                    Text(
                        "View",
                        color = Color.White,
                        fontSize = (sizeDp * 0.24f).coerceIn(10f, 14f).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
                MappingType.WheelCycle -> Text(
                    "⇅ " + (if (c.cycleIds.isEmpty()) "…" else c.cycleIds.size.toString()),
                    color = Color.White, fontSize = textSize, fontWeight = FontWeight.Bold, maxLines = 1,
                )
                MappingType.LayerSwitch -> androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("L${c.targetLayer}", color = Color.White.copy(alpha = 0.7f), fontSize = (textSize.value * 0.75f).sp, fontWeight = FontWeight.Bold)
                    BindingFace(c.binding, listening, textSize, sizeDp)
                }
                else -> BindingFace(c.binding, listening, textSize, sizeDp)
            }
        }
        // Editor: (×) apaga, engrenagem abre as opções do botão selecionado.
        if (onDelete != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 7.dp, y = (-7).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC0E1320))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    .exactTap { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.close_24px), contentDescription = stringResource(R.string.remove), tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
        if (selected && onSettings != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-7).dp, y = (-7).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0E1320))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .exactTap { onSettings() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.settings_24px), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
        // Alça de tamanho: arrastar para fora aumenta, para dentro diminui.
        if (selected && onResizeDrag != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 7.dp, y = 7.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0E1320))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onResizeDrag((drag.x + drag.y) / 2f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_resize), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** Texto de uma atribuição no controle: "…" enquanto não tem tecla. */
private fun faceText(b: InputBinding): String = if (!b.isSet) "…" else b.label()

/** O que aparece dentro do controle: a tecla, um ícone de mouse com o lado pintado, ou "…" esperando a tecla. */
@Composable
private fun BindingFace(b: InputBinding, listening: Boolean, textSize: androidx.compose.ui.unit.TextUnit, sizeDp: Float) {
    val mouseIcon = when (b.mouse) {
        MouseButton.Left -> R.drawable.ic_mouse_left
        MouseButton.Right -> R.drawable.ic_mouse_right
        MouseButton.Middle -> R.drawable.ic_mouse_middle
        MouseButton.WheelUp, MouseButton.WheelDown, MouseButton.None -> null
    }
    if (mouseIcon != null) {
        Icon(painterResource(mouseIcon), contentDescription = b.label(), tint = if (listening) NeonCyan else Color.White, modifier = Modifier.size((sizeDp * 0.5f).coerceIn(14f, 26f).dp))
        return
    }
    Text(
        if (!b.isSet) "…" else b.label(),
        color = if (listening) NeonCyan else Color.White,
        fontSize = textSize,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        lineHeight = (textSize.value * 1.05f).sp,
        overflow = TextOverflow.Clip,
        modifier = Modifier.padding(horizontal = 3.dp),
    )
}

/** FPS do jogo, bateria e sinal do Wi-Fi. */
data class GameStats(val fps: Int, val battery: Int, val charging: Boolean, val wifiBars: Int, val style: Int = 0)

@Composable
private fun FpsPill(st: GameStats) {
    val fpsColor = when {
        st.fps < 0 -> Color.White
        st.fps >= 50 -> Color(0xFF34D399)
        st.fps >= 30 -> Color(0xFFFACC15)
        else -> Color(0xFFF87171)
    }
    StatPill { Text("FPS " + (if (st.fps >= 0) st.fps.toString() else "--"), color = fpsColor, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun BatteryPill(st: GameStats) = StatPill {
    BatteryIcon(st.battery, st.charging)
    Text("${st.battery}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun WifiPill(st: GameStats) = StatPill { WifiIcon(st.wifiBars) }

@Composable
private fun StatPill(content: @Composable () -> Unit) {
    Row(
        Modifier
            .background(Color(0x8C000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * FPS, bateria e Wi-Fi em selos separados. Estilos:
 * 0 = FPS à esquerda, bateria e Wi-Fi à direita; 1 = topo centro; 2 = coluna à esquerda;
 * 3 = coluna à direita; 4 = rodapé.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.StatsLayer(st: GameStats) {
    val gap = Arrangement.spacedBy(6.dp)
    when (st.style) {
        1 -> Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), horizontalArrangement = gap) { FpsPill(st); BatteryPill(st); WifiPill(st) }
        2 -> androidx.compose.foundation.layout.Column(Modifier.align(Alignment.TopStart).padding(start = 44.dp, top = 8.dp), verticalArrangement = gap) { FpsPill(st); BatteryPill(st); WifiPill(st) }
        3 -> androidx.compose.foundation.layout.Column(Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 8.dp), verticalArrangement = gap, horizontalAlignment = Alignment.End) { FpsPill(st); BatteryPill(st); WifiPill(st) }
        4 -> Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalArrangement = gap) { FpsPill(st); BatteryPill(st); WifiPill(st) }
        else -> {
            Box(Modifier.align(Alignment.TopStart).padding(start = 44.dp, top = 6.dp)) { FpsPill(st) }
            Row(Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 6.dp), horizontalArrangement = gap) { WifiPill(st); BatteryPill(st) }
        }
    }
}

/** Ícone de bateria: contorno com o nível preenchido (verde, amarelo, vermelho; azul carregando). */
@Composable
private fun BatteryIcon(level: Int, charging: Boolean) {
    val color = when {
        charging -> Color(0xFF4D8DFF)
        level <= 15 -> Color(0xFFF87171)
        level <= 35 -> Color(0xFFFACC15)
        else -> Color(0xFF34D399)
    }
    androidx.compose.foundation.Canvas(Modifier.width(20.dp).height(10.dp)) {
        val stroke = 1.2.dp.toPx()
        val capW = 2.dp.toPx()
        val bodyW = size.width - capW
        drawRoundRect(Color.White, size = androidx.compose.ui.geometry.Size(bodyW, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRect(Color.White, topLeft = Offset(bodyW, size.height * 0.3f), size = androidx.compose.ui.geometry.Size(capW, size.height * 0.4f))
        val pad = stroke + 1f
        val fillW = (bodyW - pad * 2) * (level.coerceIn(0, 100) / 100f)
        drawRoundRect(color, topLeft = Offset(pad, pad), size = androidx.compose.ui.geometry.Size(fillW, size.height - pad * 2), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
    }
}

/** Ícone do Wi-Fi: leque de 3 arcos + ponto, acesos conforme o sinal (0 a 4). */
@Composable
private fun WifiIcon(level: Int) {
    androidx.compose.foundation.Canvas(Modifier.width(14.dp).height(11.dp)) {
        val on = Color.White
        val off = Color.White.copy(alpha = 0.25f)
        val cx = size.width / 2f
        val bottom = size.height
        val stroke = 1.6.dp.toPx()
        drawCircle(if (level >= 1) on else off, radius = 1.4.dp.toPx(), center = Offset(cx, bottom - 1.4.dp.toPx()))
        for (i in 1..3) {
            val r = size.height * (0.34f * i)
            drawArc(
                color = if (level >= i + 1) on else off,
                startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(cx - r, bottom - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

/**
 * Toque que só vale dentro do desenho. O Compose amplia a área de toque de elementos pequenos (até ~48 dp);
 * no (×) isso fazia toques perto dele apagarem o botão.
 */
private fun Modifier.exactTap(onTap: () -> Unit): Modifier = pointerInput(Unit) {
    detectTapGestures { p ->
        if (p.x >= 0f && p.y >= 0f && p.x <= size.width && p.y <= size.height) onTap()
    }
}
