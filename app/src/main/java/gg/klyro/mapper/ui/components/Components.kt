package gg.klyro.mapper.ui.components

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import gg.klyro.mapper.R
import gg.klyro.mapper.ui.theme.KlyroColors

/** Cartão expressivo Android 17 com ícone em squircle, título e resumo. */
@Composable
fun InfoCard(
    icon: Int,
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBackground: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    val shape = RoundedCornerShape(24.dp)
    val content: @Composable () -> Unit = {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (!summary.isNullOrBlank()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border) { content() }
    else Card(modifier = modifier, shape = shape, colors = colors, border = border) { content() }
}

// ---------- Peças no estilo da tela inicial (cards escuros, borda fina, botões compactos) ----------

/** Barra de topo das telas internas: botão de voltar quadrado + título. */
@Composable
fun KlyroTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            onClick = onBack,
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(12.dp),
            color = KlyroColors.CardElevated,
            border = BorderStroke(1.dp, KlyroColors.Border),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp), color = Color.White)
    }
}

/** Card padrão: fundo escuro, borda fina. */
@Composable
fun KlyroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = KlyroColors.Card,
        border = BorderStroke(1.dp, KlyroColors.Border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** Linha fina entre itens de um card. */
@Composable
fun KlyroDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(KlyroColors.Border),
    )
}

/** Botão compacto: azul (principal) ou com borda (secundário). */
@Composable
fun KlyroButton(text: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = when {
            !enabled -> KlyroColors.CardElevated
            primary -> KlyroColors.Blue
            else -> KlyroColors.CardElevated
        },
        border = if (primary && enabled) null else BorderStroke(1.dp, KlyroColors.Border),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.White else KlyroColors.TextMuted.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Passo numa lista: número (ou check quando feito), título, nota curta e ação à direita. */
@Composable
fun KlyroStep(n: Int, title: String, done: Boolean, note: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (done) KlyroColors.Green.copy(alpha = 0.16f) else KlyroColors.CardElevated)
                .border(1.dp, if (done) KlyroColors.Green.copy(alpha = 0.5f) else KlyroColors.Border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(painterResource(R.drawable.check_24px), null, tint = KlyroColors.Green, modifier = Modifier.size(14.dp))
            } else {
                Text("$n", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = KlyroColors.TextMuted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (done) KlyroColors.TextMuted else Color.White,
            )
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = KlyroColors.TextMuted)
        }
        action?.invoke()
    }
}

/** Seletor de duas ou mais opções, no lugar de abas. */
@Composable
fun KlyroSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(KlyroColors.Card)
            .border(1.dp, KlyroColors.Border, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Surface(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(9.dp),
                color = if (active) KlyroColors.CardElevated else Color.Transparent,
                border = if (active) BorderStroke(1.dp, KlyroColors.Border) else null,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color.White else KlyroColors.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 9.dp),
                )
            }
        }
    }
}

/** Ícone de um app (Drawable do PackageManager) convertido para Compose com moldura squircle Android 17. */
@Composable
fun AppIcon(drawable: Drawable?, modifier: Modifier = Modifier.size(50.dp)) {
    val bitmap = remember(drawable) { drawable?.toBitmap(144, 144)?.asImageBitmap() }
    val shape = RoundedCornerShape(16.dp)
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            modifier = modifier
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape),
        )
    } else {
        Box(
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

/** Slider fino do Klyro: trilho de 3 dp e bolinha branca. Toque ou arraste para mudar. */
@Composable
fun KlyroSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val latest = androidx.compose.runtime.rememberUpdatedState(onChange)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(24.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
        val frac = ((value - range.start) / span).coerceIn(0f, 1f)
        fun setAt(x: Float) = latest.value(range.start + (x / widthPx).coerceIn(0f, 1f) * span)
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .align(Alignment.CenterStart)
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(3.dp)
                .align(Alignment.CenterStart)
                .background(KlyroColors.Blue, RoundedCornerShape(2.dp)),
        )
        val thumb = 14.dp
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = ((maxWidth - thumb) * frac).coerceAtLeast(0.dp))
                .size(thumb)
                .background(Color.White, CircleShape),
        )
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(range) { detectTapGestures { setAt(it.x) } }
                .pointerInput(range) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        setAt(change.position.x)
                    }
                },
        )
    }
}
