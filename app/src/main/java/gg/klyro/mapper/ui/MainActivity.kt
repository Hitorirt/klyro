package gg.klyro.mapper.ui

import gg.klyro.mapper.ui.tr
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import gg.klyro.mapper.game.GameRepository
import gg.klyro.mapper.mapping.MappingRepository
import gg.klyro.mapper.ui.screens.ActivationScreen
import gg.klyro.mapper.ui.screens.AddGamesScreen
import gg.klyro.mapper.ui.screens.HomeScreen
import gg.klyro.mapper.ui.screens.PairingScreen
import gg.klyro.mapper.ui.theme.KlyroTheme

object Routes {
    const val HOME = "home"
    const val ADD_GAMES = "add_games"
    const val PAIRING = "pairing"
    const val ACTIVATION = "activation"
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(gg.klyro.mapper.ui.Lang.wrap(newBase))
    }

    private val importedNotice = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingConfigIntent(intent)
        setContent {
            KlyroTheme {
                val nav = rememberNavController()
                val slide = tween<androidx.compose.ui.unit.IntOffset>(280)
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, slide) },
                    exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, slide) },
                    popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, slide) },
                    popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, slide) },
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onAddGame = { nav.navigate(Routes.ADD_GAMES) },
                            onOpenActivation = { nav.navigate(Routes.ACTIVATION) },
                            externalNotice = importedNotice.value,
                            onDismissNotice = { importedNotice.value = null },
                            onImportUri = { uri -> importFromUri(uri) },
                        )
                    }
                    composable(Routes.ADD_GAMES) { AddGamesScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.PAIRING) { PairingScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.ACTIVATION) { ActivationScreen(onBack = { nav.popBackStack() }, onOpenPairing = { nav.navigate(Routes.PAIRING) }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingConfigIntent(intent)
    }

    /**
     * Quando o usuário clica num arquivo `.krylogg` no gerenciador de arquivos, WhatsApp, Telegram ou Discord,
     * o Android abre o Klyro automaticamente por aqui e já importa o perfil no jogo correspondente!
     */
    private fun handleIncomingConfigIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW || uri.toString().isNotBlank()) {
            importFromUri(uri)
        }
    }

    private fun importFromUri(uri: Uri) {
        runCatching {
            val rawText = if (MappingRepository.SCHEMES.any { uri.scheme.equals(it, ignoreCase = true) }) {
                uri.toString()
            } else {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            val repo = MappingRepository(this)
            val result = repo.importWithGame(rawText)
            if (result == null) {
                val msg = tr("File rejected: this is not a Klyro config.", "Arquivo recusado: isso não é uma config do Klyro.")
                importedNotice.value = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return
            }
            val (profile, targetGame) = result
            val rawGames = getSharedPreferences("game", MODE_PRIVATE).getString("games", "[]") ?: "[]"
            val addedGames = runCatching {
                org.json.JSONArray(rawGames).let { a -> List(a.length()) { a.getString(it) } }
            }.getOrDefault(emptyList())

            // Salva a config no jogo de origem (se estiver no arquivo), no perfil global e nos jogos adicionados
            val targets = buildSet {
                if (!targetGame.isNullOrBlank()) add(targetGame)
                add(null)
                addedGames.forEach { add(it) }
            }
            targets.forEach { pkg ->
                val gp = repo.loadProfiles(pkg)
                val updated = gp.copy(activeId = profile.id, profiles = gp.profiles + profile)
                repo.saveProfiles(pkg, updated)
            }
            val msg = tr("Config '${profile.name}.krylogg' (${profile.mapping.components.size} controls) imported and activated!", "Config '${profile.name}.krylogg' (${profile.mapping.components.size} controles) importada e ativada!")
            importedNotice.value = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }.onFailure {
            val msg = tr("Could not open the .krylogg file: ${it.message}", "Não foi possível abrir o arquivo .krylogg: ${it.message}")
            importedNotice.value = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
}
