package gg.klyro.mapper.overlay

import androidx.compose.foundation.layout.heightIn
import gg.klyro.mapper.ui.tr
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import gg.klyro.mapper.ui.theme.KlyroColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.klyro.mapper.R
import gg.klyro.mapper.inject.TouchInjector
import gg.klyro.mapper.input.InputSummary
import gg.klyro.mapper.mapping.ControlsSettings
import gg.klyro.mapper.mapping.GameProfiles
import gg.klyro.mapper.mapping.MappingType
import gg.klyro.mapper.mapping.label

// Paleta minimalista ultra limpa idêntica à tela inicial (HomeScreen)
private val SheetBg = KlyroColors.Background.copy(alpha = 0.97f)
private val SheetCard = KlyroColors.Card
private val SheetCardElevated = KlyroColors.CardElevated
private val SheetBorder = KlyroColors.Border
private val SheetTextMuted = KlyroColors.TextMuted
private val SheetGreen = KlyroColors.Green
private val SheetRed = KlyroColors.Red

/** Botão flutuante: o mascote do Klyro espiando da borda esquerda. Toque abre o menu, arrastar move na vertical. */
@Composable
fun KlyroBubble(onTap: () -> Unit, onDragY: (Float) -> Unit) {
    Image(
        painter = painterResource(R.drawable.klyro_peek),
        contentDescription = stringResource(R.string.app_name),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .pointerInput(Unit) { detectVerticalDragGestures { change, dy -> change.consume(); onDragY(dy) } },
    )
}

data class SheetState(
    val controlsOn: Boolean,
    val showControls: Boolean,
    val locked: Boolean,
    val settings: ControlsSettings,
    val buttonCount: Int,
    val profiles: GameProfiles?,
    val devices: InputSummary,
)

/**
 * Painel lateral dentro do jogo ("Controles do jogo") — minimalista, direto e bem organizado em 3 blocos limpos:
 *  1. Cabeçalho + barra compacta de status (Motor · Teclado · Mouse)
 *  2. Ações principais (Botão "Editar HUD no jogo", botão "Travar Mouse [Ctrl]" e seletor de config .krylogg)
 *  3. Ajustes unificados (Controles ativos, Mostrar HUD, Opacidade e Sensibilidade num único bloco limpo)
 */
@Composable
fun OverlaySheet(
    gamePackage: String?,
    state: SheetState,
    onProfile: (String) -> Unit,
    onControlsOn: (Boolean) -> Unit,
    onShowControls: (Boolean) -> Unit,
    onOpacity: (Float) -> Unit,
    onShowStats: (Boolean) -> Unit,
    onStatsStyle: (Int) -> Unit,
    onSensitivity: (Float) -> Unit,
    onLock: () -> Unit,
    onStartInjector: () -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val touch by remember { TouchInjector.get(context) }.status.collectAsStateWithLifecycle()
    val touchReady = touch.ready
    val gameName = remember(gamePackage) {
        gamePackage?.let { pkg ->
            runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
        }
    }
    val aim = state.profiles?.active?.mapping?.components?.firstOrNull {
        (it.type == MappingType.MouseUnlock || it.type == MappingType.Aim) && it.binding.keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN
    } ?: state.profiles?.active?.mapping?.components?.firstOrNull {
        it.type == MappingType.MouseUnlock && it.binding.isSet
    }
    val lockKey = aim?.binding?.takeIf { it.isSet }?.label() ?: "Ctrl"

    val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 16).dp
    Surface(
        modifier = Modifier.width(340.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFA121722),
        contentColor = Color.White,
    ) {
        Column(
            Modifier
                .heightIn(max = maxH)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Logo, jogo, status e fechar
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painter = painterResource(R.drawable.klyro_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        gameName ?: stringResource(R.string.overlay_controls_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (touchReady) tr("Active", "Ativo") else tr("Inactive", "Inativo")) + " · " +
                            (if (state.devices.hasKeyboard) tr("Keyboard OK", "Teclado OK") else tr("No keyboard", "Sem teclado")) +
                            " · " + (if (state.devices.hasMouse) "Mouse OK" else tr("No mouse", "Sem mouse")),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = SheetTextMuted,
                        maxLines = 1,
                    )
                }
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.close_24px), stringResource(R.string.close), tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }

            if (!touchReady) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF281317))
                        .clickable(onClick = onStartInjector)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(tr("Service stopped", "Serviço parado"), style = MaterialTheme.typography.labelSmall, color = SheetRed)
                    Text(tr("Reconnect →", "Reconectar →"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // Editar e Mira
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MiniAction(R.drawable.ic_stat_klyro, tr("Edit HUD", "Editar HUD"), highlighted = true, onClick = onEdit, modifier = Modifier.weight(1f))
                MiniAction(R.drawable.gi_unlock, tr("Aim ($lockKey)", "Mira ($lockKey)"), highlighted = state.locked, onClick = onLock, modifier = Modifier.weight(1f))
            }

            // Perfis
            state.profiles?.let { gp ->
                if (gp.profiles.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        gp.profiles.forEach { p ->
                            MiniChip(p.name, p.id == gp.activeId) { onProfile(p.id) }
                        }
                    }
                }
            }

            // Chaves
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MiniChip(tr("Controls", "Controles"), state.controlsOn, Modifier.weight(1f)) { onControlsOn(!state.controlsOn) }
                MiniChip(tr("Buttons", "Botões"), state.showControls, Modifier.weight(1f)) { onShowControls(!state.showControls) }
                MiniChip("FPS", state.settings.showStats, Modifier.weight(1f)) { onShowStats(!state.settings.showStats) }
            }

            val sliderColors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = KlyroColors.Blue,
                inactiveTrackColor = SheetCardElevated,
            )
            if (state.settings.showStats) {
                Text(tr("Status position", "Posição do status"), style = MaterialTheme.typography.labelSmall, color = SheetTextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        tr("Split", "Lados"), tr("Top", "Topo"), tr("Left", "Esq."), tr("Right", "Dir."), tr("Bottom", "Baixo"),
                    ).forEachIndexed { i, name ->
                        MiniChip(name, state.settings.statsStyle == i, Modifier.weight(1f)) { onStatsStyle(i) }
                    }
                }
            }
            MiniSlider(tr("Opacity", "Opacidade"), "${(state.settings.opacity * 100).toInt()}%", state.settings.opacity, 0f..1f, onOpacity, sliderColors)
            MiniSlider(tr("Aim speed", "Velocidade da mira"), "%.2f".format(state.settings.mouseSensitivity), state.settings.mouseSensitivity, 0.2f..4f, onSensitivity, sliderColors)

            Text(
                stringResource(R.string.overlay_stop),
                style = MaterialTheme.typography.labelSmall,
                color = SheetRed,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MiniAction(icon: Int, text: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) KlyroColors.Blue else SheetCardElevated)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun MiniChip(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Color.White else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF121722) else SheetTextMuted, maxLines = 1)
    }
}

@Composable
private fun MiniSlider(
    title: String,
    value: String,
    v: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    colors: androidx.compose.material3.SliderColors,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelSmall, color = SheetTextMuted)
        }
        gg.klyro.mapper.ui.components.KlyroSlider(value = v, range = range, onChange = onChange)
    }
}

@Composable
private fun ValuePill(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SheetCardElevated)
            .border(1.dp, SheetBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text(summary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = SheetTextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = KlyroColors.Blue,
                uncheckedThumbColor = SheetTextMuted,
                uncheckedTrackColor = SheetCardElevated,
                uncheckedBorderColor = SheetBorder,
            ),
        )
    }
}
