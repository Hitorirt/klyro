package gg.klyro.mapper.game

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Lista de jogos do usuário, salva nas preferências (só os nomes de pacote). */
class GameRepository private constructor(context: Context) {

    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences("game", Context.MODE_PRIVATE)

    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games.asStateFlow()

    /** Carrega a lista salva e tira o que foi desinstalado. */
    suspend fun load() = withContext(Dispatchers.IO) {
        val installed = savedPackages().mapNotNull { Game.load(pm, it) }
        _games.value = installed
        save(installed)
    }

    fun add(game: Game) {
        if (_games.value.any { it.packageName == game.packageName }) return
        update(_games.value + game)
    }

    fun remove(packageName: String) = update(_games.value.filterNot { it.packageName == packageName })

    /**
     * Apps que abrem pela tela inicial, sem os do sistema e sem este app, em ordem alfabética.
     * Usa a consulta de "launcher" declarada no manifesto (<queries>), que funciona no Android 11+
     * sem a permissão de ver todos os apps.
     */
    suspend fun installedApps(selfPackage: String = ""): List<Game> = withContext(Dispatchers.IO) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != selfPackage }
            .map { Game.from(pm, it) }
            .sortedBy { it.name.lowercase() }
    }

    private fun update(list: List<Game>) {
        _games.value = list
        save(list)
    }

    private fun savedPackages(): List<String> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    }

    private fun save(list: List<Game>) {
        prefs.edit().putString(KEY, JSONArray(list.map { it.packageName }).toString()).apply()
    }

    companion object {
        private const val KEY = "games"

        @Volatile
        private var instance: GameRepository? = null

        fun get(context: Context): GameRepository =
            instance ?: synchronized(this) {
                instance ?: GameRepository(context.applicationContext).also { instance = it }
            }
    }
}
