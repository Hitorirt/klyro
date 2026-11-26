package gg.klyro.mapper.ui.screens

import gg.klyro.mapper.ui.tr
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import gg.klyro.mapper.ui.theme.KlyroColors
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.klyro.mapper.R
import gg.klyro.mapper.adb.AdbRepository
import gg.klyro.mapper.data.ActivationMethod
import gg.klyro.mapper.data.Settings
import gg.klyro.mapper.game.Game
import gg.klyro.mapper.game.GameRepository
import gg.klyro.mapper.inject.Backend
import gg.klyro.mapper.inject.TouchInjector
import gg.klyro.mapper.input.InputSummary
import gg.klyro.mapper.input.inputSummary
import gg.klyro.mapper.mapping.MappingRepository
import gg.klyro.mapper.overlay.OverlayService
import gg.klyro.mapper.ui.components.AppIcon

// Cores minimalistas estilo monocromático escuro + detalhes precisos
private val MinimalBg = KlyroColors.Background
private val MinimalCard = KlyroColors.Card
private val MinimalCardElevated = KlyroColors.CardElevated
private val MinimalBorder = KlyroColors.Border
private val MinimalTextMuted = KlyroColors.TextMuted
private val MinimalGreen = KlyroColors.Green

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onAddGame: () -> Unit,
    onOpenActivation: () -> Unit,
    externalNotice: String? = null,
    onDismissNotice: () -> Unit = {},
    onImportUri: (Uri) -> Unit = {},
) {
    val context = LocalContext.current
    val adb = remember { AdbRepository.get(context) }
    val games = remember { GameRepository.get(context) }
    val settings = remember { Settings(context) }
    val mappingRepo = remember { MappingRepository(context) }

    val injector = remember { TouchInjector.get(context) }
    val touch by injector.status.collectAsStateWithLifecycle()
    val gameList by games.games.collectAsStateWithLifecycle()
    val devices by remember { inputSummary(context) }.collectAsStateWithLifecycle(InputSummary())

    var toDelete by remember { mutableStateOf<Game?>(null) }
    var configGame by remember { mutableStateOf<Game?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onImportUri(uri)
            refreshTick++
        }
    }

    LaunchedEffect(Unit) {
        games.load()
        if (settings.activationMethod == ActivationMethod.WirelessDebugging) adb.autoConnect()
        injector.start()
    }

    val connected = touch.ready
    val activeNotice = externalNotice ?: localNotice

    fun launchGame(pkg: String) {
        if (!AndroidSettings.canDrawOverlays(context)) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            ContextCompat.startForegroundService(context, OverlayService.intent(context, pkg))
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
        }
    }

    Scaffold(
        containerColor = MinimalBg,
        topBar = {
            // TopBar minimalista com a estrela geométrica de 6 pontas
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MinimalCardElevated,
                    border = BorderStroke(1.dp, MinimalBorder),
                ) {
                    Image(
                        painter = painterResource(R.drawable.klyro_logo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                        color = Color.White,
                    )
                    Text(
                        when (touch.backend) {
                            Backend.Shizuku -> tr("Shizuku active", "Shizuku ativo")
                            Backend.Adb -> tr("Wireless ADB active", "ADB sem fio ativo")
                            Backend.None -> tr("Service inactive", "Serviço inativo")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) MinimalGreen else MinimalTextMuted,
                    )
                }

                // Botão rápido para abrir/importar arquivo .krylogg
                Surface(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(11.dp),
                    color = MinimalCardElevated,
                    border = BorderStroke(1.dp, MinimalBorder),
                ) {
                    Row(
                        Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_stat_klyro),
                            contentDescription = tr("Import .krylogg", "Importar .krylogg"),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            ".krylogg",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }

                // Botão de Configurações / Ativação
                Surface(
                    onClick = onOpenActivation,
                    shape = RoundedCornerShape(11.dp),
                    color = MinimalCardElevated,
                    border = BorderStroke(1.dp, MinimalBorder),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.settings_24px),
                            contentDescription = stringResource(R.string.activation_title),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = 36.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Banner de aviso quando um arquivo .krylogg é aberto/importado
            if (activeNotice != null) {
                item {
                    Surface(
                        onClick = {
                            localNotice = null
                            onDismissNotice()
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF13221C),
                        border = BorderStroke(1.dp, MinimalGreen.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_stat_klyro),
                                contentDescription = null,
                                tint = MinimalGreen,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                activeNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                painterResource(R.drawable.close_24px),
                                contentDescription = tr("Close", "Fechar"),
                                tint = MinimalTextMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // 1. Barra unificada minimalista de Status + Periféricos (inspirada no DualTopBar do KeySync)
            item {
                Surface(
                    onClick = onOpenActivation,
                    shape = RoundedCornerShape(16.dp),
                    color = MinimalCard,
                    border = BorderStroke(1.dp, MinimalBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(if (connected) MinimalGreen else Color(0xFFF87171)),
                                )
                                Column {
                                    Text(
                                        if (connected) tr("Ready", "Motor de injeção pronto") else tr("Activation required", "Ativação necessária"),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                    )
                                    Text(
                                        if (connected) tr("Tap the floating button in-game to open the menu", "Toque na estrela dentro do jogo para abrir o menu")
                                        else tr("Tap here to activate with Shizuku or Wireless debugging", "Toque aqui para ativar via Shizuku ou Depuração sem fio"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MinimalTextMuted,
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MinimalCardElevated,
                                border = BorderStroke(1.dp, MinimalBorder),
                            ) {
                                Text(
                                    if (connected) "Configurar" else tr("Activate", "Ativar"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }

                        HorizontalDivider(color = MinimalBorder)

                        // Linha compacta de Teclado e Mouse (sem poluição de nomes internos)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MinimalDevicePill(
                                icon = R.drawable.keyboard_24px,
                                label = tr("Keyboard", "Teclado"),
                                status = if (devices.hasKeyboard) tr("Connected", "Conectado") else "Ausente",
                                active = devices.hasKeyboard,
                                modifier = Modifier.weight(1f),
                            )
                            MinimalDevicePill(
                                icon = R.drawable.gi_unlock,
                                label = "Mouse",
                                status = if (devices.hasMouse) tr("Connected", "Conectado") else "Ausente",
                                active = devices.hasMouse,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 2. Cabeçalho da lista de Jogos
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            tr("Games", "Jogos"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(
                            "${gameList.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MinimalTextMuted,
                        )
                    }
                    Surface(
                        onClick = onAddGame,
                        shape = RoundedCornerShape(10.dp),
                        color = KlyroColors.Blue,
                        contentColor = Color.White,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(painterResource(R.drawable.add_24px), null, modifier = Modifier.size(15.dp))
                            Text(
                                tr("Add", "Adicionar"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // Estado vazio minimalista
            if (gameList.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MinimalCard,
                        border = BorderStroke(1.dp, MinimalBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_stat_klyro),
                                contentDescription = null,
                                tint = MinimalTextMuted,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                stringResource(R.string.no_games),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MinimalTextMuted,
                            )
                            OutlinedButton(
                                onClick = onAddGame,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MinimalBorder),
                            ) {
                                Text(stringResource(R.string.add_game), color = Color.White)
                            }
                        }
                    }
                }
            }

            // Lista minimalista de jogos (cada jogo tem seu chip de config .krylogg e botão Jogar)
            items(gameList, key = { it.packageName }) { game ->
                val profiles = remember(game.packageName, refreshTick) { mappingRepo.loadProfiles(game.packageName) }
                val activeProfile = profiles.active

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = { if (connected) launchGame(game.packageName) else onOpenActivation() },
                            onLongClick = { toDelete = game },
                        ),
                    shape = RoundedCornerShape(16.dp),
                    color = MinimalCard,
                    border = BorderStroke(1.dp, MinimalBorder),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(game.icon, modifier = Modifier.size(46.dp))

                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                game.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Chip clicável da config ativa (.krylogg) para exportar/compartilhar/trocar direto da Home
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MinimalCardElevated)
                                    .border(1.dp, MinimalBorder, RoundedCornerShape(6.dp))
                                    .clickable { configGame = game }
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_stat_klyro),
                                    contentDescription = null,
                                    tint = MinimalTextMuted,
                                    modifier = Modifier.size(10.dp),
                                )
                                Text(
                                    "${activeProfile.name}.krylogg · ${activeProfile.mapping.components.size} teclas",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = MinimalTextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Botão de Configs (.krylogg) do jogo
                        IconButton(
                            onClick = { configGame = game },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.open_in_new_24px),
                                contentDescription = tr("Export / .krylogg configs", "Exportar / Configs .krylogg"),
                                tint = MinimalTextMuted,
                                modifier = Modifier.size(17.dp),
                            )
                        }

                        // Botão de remover jogo
                        IconButton(
                            onClick = { toDelete = game },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.trash_24px),
                                contentDescription = stringResource(R.string.remove),
                                tint = MinimalTextMuted,
                                modifier = Modifier.size(17.dp),
                            )
                        }

                        // Botão Jogar minimalista
                        Surface(
                            onClick = { if (connected) launchGame(game.packageName) else onOpenActivation() },
                            shape = RoundedCornerShape(10.dp),
                            color = KlyroColors.Blue,
                            contentColor = Color.White,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(painterResource(R.drawable.play_arrow_24px), null, modifier = Modifier.size(15.dp))
                                Text(
                                    stringResource(R.string.play),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal rápido de Configs `.krylogg` para o jogo clicado na Home
    configGame?.let { game ->
        var gp by remember(game.packageName, refreshTick) { mutableStateOf(mappingRepo.loadProfiles(game.packageName)) }
        var pasteCode by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { configGame = null },
            containerColor = MinimalCard,
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_stat_klyro), null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(tr(".krylogg configs · ${game.name}", "Configs .krylogg · ${game.name}"), style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Arquivos .krylogg: ao enviar para alguém, basta tocar no arquivo para o app abrir e importar sozinho.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MinimalTextMuted,
                    )
                    gp.profiles.forEach { p ->
                        val isActive = p.id == gp.activeId
                        Surface(
                            onClick = {
                                gp = gp.copy(activeId = p.id)
                                mappingRepo.saveProfiles(game.packageName, gp)
                                refreshTick++
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isActive) MinimalCardElevated else MinimalBg,
                            border = BorderStroke(1.dp, if (isActive) Color.White else MinimalBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(p.name, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(tr("${p.mapping.components.size} controls", "${p.mapping.components.size} controles"), style = MaterialTheme.typography.labelSmall, color = MinimalTextMuted)
                                }
                                if (isActive) {
                                    Text(tr("Active", "Ativa"), style = MaterialTheme.typography.labelSmall, color = MinimalGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                runCatching {
                                    mappingRepo.shareConfigFile(context, gp.active, game.packageName)
                                }.onFailure {
                                    val code = mappingRepo.export(gp.active, game.packageName)
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("Klyro Config", code))
                                    localNotice = tr(".krylogg code copied!", "Código .krylogg copiado!")
                                }
                                configGame = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = KlyroColors.Blue, contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(tr("Share .krylogg", "Compartilhar .krylogg"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        FilledTonalButton(
                            onClick = {
                                val file = mappingRepo.saveConfigFile(context, gp.active, game.packageName)
                                val code = mappingRepo.export(gp.active, game.packageName)
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Klyro Config", code))
                                localNotice = tr("Saved: ${file.name} (and copied)", "Salvo: ${file.name} (e copiado)")
                                configGame = null
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(tr("Copy", "Copiar"), fontSize = 12.sp)
                        }
                    }

                    HorizontalDivider(color = MinimalBorder)

                    OutlinedTextField(
                        value = pasteCode,
                        onValueChange = { pasteCode = it },
                        placeholder = { Text(tr("Paste a KLYRO-V1: code...", "Colar código KLYRO-V1:..."), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                configGame = null
                                filePicker.launch(arrayOf("*/*"))
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(tr("Open .krylogg file", "Abrir arquivo .krylogg"), fontSize = 12.sp, color = Color.White)
                        }
                        FilledTonalButton(
                            onClick = {
                                val raw = pasteCode.ifBlank {
                                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                    clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                }
                                val imported = mappingRepo.import(raw)
                                if (imported != null) {
                                    gp = gp.copy(activeId = imported.id, profiles = gp.profiles + imported)
                                    mappingRepo.saveProfiles(game.packageName, gp)
                                    refreshTick++
                                    localNotice = tr("Config '${imported.name}.krylogg' imported!", "Config '${imported.name}.krylogg' importada!")
                                    configGame = null
                                } else {
                                    localNotice = tr("Invalid code: this is not a Klyro config", "Código inválido: isso não é uma config do Klyro")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(tr("Import", "Importar"), fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { configGame = null }) {
                    Text(tr("Close", "Fechar"), color = Color.White)
                }
            },
        )
    }

    toDelete?.let { game ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = MinimalCard,
            title = { Text(stringResource(R.string.remove_game_title), color = Color.White) },
            text = { Text(stringResource(R.string.remove_game_message, game.name), color = MinimalTextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    games.remove(game.packageName)
                    toDelete = null
                }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) {
                    Text(stringResource(android.R.string.cancel), color = Color.White)
                }
            },
        )
    }
}

@Composable
private fun MinimalDevicePill(
    icon: Int,
    label: String,
    status: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(MinimalCardElevated)
            .border(1.dp, MinimalBorder, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (active) Color.White else MinimalTextMuted,
            modifier = Modifier.size(17.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MinimalGreen else MinimalTextMuted,
            )
        }
    }
}
