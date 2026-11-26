package gg.klyro.mapper.mapping

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.view.KeyEvent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Configurações dos controles na tela, iguais para todos os jogos. */
data class ControlsSettings(
    /** Opacidade dos controles mostrados por cima do jogo (0 a 1). */
    val opacity: Float = 0.5f,
    /** Sensibilidade geral do mouse na mira (multiplica a de cada mira). */
    val mouseSensitivity: Float = 1f,
    /** Mostra FPS do jogo, bateria e Wi-Fi por cima do jogo. */
    val showStats: Boolean = true,
    /** Posição dos selos de status (0 a 4, ver StatsLayer). */
    val statsStyle: Int = 0,
)

/**
 * Atalhos globais (fora do perfil): tecla que liga/desliga os controles e aparelhos ignorados.
 * Ficam em "main" para a tela de configurações e o overlay lerem a mesma coisa.
 */
object Hotkeys {
    /** Tecla que liga/desliga os controles (0 = nenhuma). */
    @Volatile var toggleKey: Int = 0
    /** Nomes dos teclados/mouses que o Klyro deve ignorar. */
    @Volatile var ignoredDevices: Set<String> = emptySet()

    fun load(context: android.content.Context) {
        val p = context.getSharedPreferences("main", android.content.Context.MODE_PRIVATE)
        toggleKey = p.getInt("toggleKey", KeyEvent.KEYCODE_F12)
        ignoredDevices = p.getStringSet("ignoredDevices", emptySet())?.toSet() ?: emptySet()
    }

    fun save(context: android.content.Context) {
        context.getSharedPreferences("main", android.content.Context.MODE_PRIVATE).edit()
            .putInt("toggleKey", toggleKey)
            .putStringSet("ignoredDevices", ignoredDevices)
            .apply()
    }
}

/** Um perfil de controles de um jogo. */
data class Profile(val id: String, val name: String, val mapping: HudMapping)

/** Perfis de um jogo e qual está em uso. */
data class GameProfiles(val activeId: String, val profiles: List<Profile>) {
    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
}

/**
 * Perfis de controles por jogo (nome do pacote) e configurações gerais. Tudo em JSON nas preferências.
 * O mapeamento salvo pela versão anterior vira automaticamente o perfil "Padrão".
 */
class MappingRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mappings", Context.MODE_PRIVATE)

    // ---------- perfis ----------

    fun loadProfiles(game: String?): GameProfiles {
        prefs.getString(profilesKey(game), null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                val arr = o.getJSONArray("profiles")
                val list = List(arr.length()) { profileFromJson(arr.getJSONObject(it)) }
                if (list.isNotEmpty()) return GameProfiles(o.optString("active", list.first().id), list)
            }
        }
        // Migração da versão anterior (um mapeamento por jogo) ou perfil vazio.
        val legacy = prefs.getString("mapping:" + (game ?: "_default"), null)
            ?.let { runCatching { componentsFromJson(JSONArray(it)) }.getOrNull() }
        val first = Profile(newId(), DEFAULT_NAME, HudMapping(legacy.orEmpty()))
        return GameProfiles(first.id, listOf(first)).also { saveProfiles(game, it) }
    }

    fun saveProfiles(game: String?, data: GameProfiles) {
        val arr = JSONArray()
        data.profiles.forEach { arr.put(profileToJson(it)) }
        prefs.edit().putString(profilesKey(game), JSONObject().put("active", data.activeId).put("profiles", arr).toString()).apply()
    }

    /** Mapeamento do perfil em uso. */
    fun load(game: String?): HudMapping = loadProfiles(game).active.mapping

    // ---------- exportar / importar (.krylogg) ----------

    /**
     * Gera o conteúdo do arquivo `.krylogg` (cabeçalho `KLYRO-V1:` + payload Base64).
     */
    fun export(profile: Profile, gamePackage: String? = null): String {
        val json = profileToJson(profile)
            .put("app", EXPORT_TAG)
            .put("magic", CONFIG_MAGIC)
            .put("version", 1)
            .apply { if (!gamePackage.isNullOrBlank()) put("game", gamePackage) }
            .toString()
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "$CONFIG_HEADER$b64"
    }

    /** Exporta o JSON legível para visualização rápida no editor. */
    fun exportPrettyJson(profile: Profile, gamePackage: String? = null): String =
        profileToJson(profile)
            .put("app", EXPORT_TAG)
            .put("magic", CONFIG_MAGIC)
            .put("version", 1)
            .apply { if (!gamePackage.isNullOrBlank()) put("game", gamePackage) }
            .toString(2)

    /**
     * Lê um arquivo `.krylogg`, um código `KLYRO-V1:` ou o JSON exportado.
     * Devolve `Pair(Profile, gamePackage?)` ou `null` se não for uma config válida.
     */
    fun importWithGame(text: String): Pair<Profile, String?>? = runCatching {
        val clean = text.trim()
        val jsonStr = when {
            HEADERS.any { clean.startsWith(it) } -> {
                val payload = clean.substringAfter(':').trim()
                String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            }
            SCHEMES.any { clean.startsWith("$it://", ignoreCase = true) } -> {
                val payload = clean.substringAfter("data=", clean.substringAfterLast("/")).trim()
                String(Base64.decode(payload, Base64.URL_SAFE or Base64.DEFAULT), Charsets.UTF_8)
            }
            else -> clean
        }
        val o = JSONObject(jsonStr)
        if (o.optString("app") !in TAGS && o.optString("magic") !in MAGICS) return null
        val profile = profileFromJson(o).copy(id = newId())
        val gamePkg = o.optString("game").takeIf { it.isNotBlank() }
        profile to gamePkg
    }.getOrNull()

    /** Lê um perfil exportado. Devolve null se o texto não for um perfil válido. */
    fun import(text: String): Profile? = importWithGame(text)?.first

    /** Salva a config como arquivo `.krylogg` exclusivo no armazenamento do app. */
    fun saveConfigFile(context: Context, profile: Profile, gamePackage: String? = null): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "configs").apply { mkdirs() }
        val safeName = profile.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "config" }
        val file = File(dir, "$safeName$CONFIG_EXT")
        file.writeText(export(profile, gamePackage), Charsets.UTF_8)
        return file
    }

    /** Compartilha o arquivo `.krylogg` exclusivo via FileProvider (WhatsApp, Telegram, Discord, Arquivos etc.). */
    fun shareConfigFile(context: Context, profile: Profile, gamePackage: String? = null) {
        val cacheDir = File(context.cacheDir, "shared_configs").apply { mkdirs() }
        val safeName = profile.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "config" }
        val file = File(cacheDir, "$safeName$CONFIG_EXT")
        val content = export(profile, gamePackage)
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_SUBJECT, "${profile.name}$CONFIG_EXT")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, "Compartilhar ${profile.name}$CONFIG_EXT")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Cria um preset padrão estilo FPS / Battle Royale (WASD, Mira View, Destravar Ctrl, Tiro Mouse-L, Pular Espaço, Agachar C, Recarregar R). */
    fun defaultPreset(screenWidth: Float, screenHeight: Float, density: Float): HudMapping {
        val w = maxOf(screenWidth, screenHeight)
        val h = minOf(screenWidth, screenHeight)
        return HudMapping(
            listOf(
                MappingComponent(
                    id = 1,
                    type = MappingType.Dpad,
                    x = w * 0.10f,
                    y = h * 0.56f,
                    size = 128f * density,
                    sensitivity = 1f,
                ),
                MappingComponent(
                    id = 2,
                    type = MappingType.Aim,
                    binding = InputBinding.NONE,
                    label = "View",
                    x = w * 0.62f,
                    y = h * 0.14f,
                    size = 46f * density,
                    sensitivity = 1.25f,
                ),
                MappingComponent(
                    id = 3,
                    type = MappingType.MouseUnlock,
                    binding = InputBinding.key(KeyEvent.KEYCODE_CTRL_LEFT),
                    x = w * 0.08f,
                    y = h * 0.10f,
                    size = 40f * density,
                ),
                MappingComponent(
                    id = 4,
                    type = MappingType.FireButton,
                    binding = InputBinding.mouse(MouseButton.Left),
                    x = w * 0.78f,
                    y = h * 0.62f,
                    size = 44f * density,
                ),
                MappingComponent(
                    id = 5,
                    type = MappingType.Button,
                    binding = InputBinding.mouse(MouseButton.Right),
                    label = "Mirar (ADS)",
                    x = w * 0.88f,
                    y = h * 0.48f,
                    size = 40f * density,
                ),
                MappingComponent(
                    id = 6,
                    type = MappingType.Button,
                    binding = InputBinding.key(KeyEvent.KEYCODE_SPACE),
                    label = "Pular",
                    x = w * 0.90f,
                    y = h * 0.66f,
                    size = 40f * density,
                ),
                MappingComponent(
                    id = 7,
                    type = MappingType.Button,
                    binding = InputBinding.key(KeyEvent.KEYCODE_C),
                    label = "Agachar",
                    x = w * 0.91f,
                    y = h * 0.84f,
                    size = 38f * density,
                ),
                MappingComponent(
                    id = 8,
                    type = MappingType.Button,
                    binding = InputBinding.key(KeyEvent.KEYCODE_R),
                    label = "Recarregar",
                    x = w * 0.87f,
                    y = h * 0.06f,
                    size = 38f * density,
                ),
                MappingComponent(
                    id = 9,
                    type = MappingType.Button,
                    binding = InputBinding.key(KeyEvent.KEYCODE_SHIFT_LEFT),
                    label = "Correr",
                    x = w * 0.14f,
                    y = h * 0.42f,
                    size = 40f * density,
                ),
                MappingComponent(
                    id = 10,
                    type = MappingType.Button,
                    binding = InputBinding.key(KeyEvent.KEYCODE_TAB),
                    label = "Mochila",
                    x = w * 0.03f,
                    y = h * 0.57f,
                    size = 40f * density,
                ),
            ),
        )
    }

    // ---------- configurações gerais ----------

    fun loadSettings() = ControlsSettings(
        opacity = prefs.getFloat("opacity", 0.5f),
        mouseSensitivity = prefs.getFloat("mouseSensitivity", 1f),
        showStats = prefs.getBoolean("showStats", true),
        statsStyle = prefs.getInt("statsStyle", 0),
    )

    fun saveSettings(s: ControlsSettings) {
        prefs.edit().putFloat("opacity", s.opacity).putFloat("mouseSensitivity", s.mouseSensitivity).putBoolean("showStats", s.showStats).putInt("statsStyle", s.statsStyle).apply()
    }

    // ---------- JSON ----------

    private fun profileToJson(p: Profile) = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("components", componentsToJson(p.mapping.components))

    private fun profileFromJson(o: JSONObject) = Profile(
        id = o.optString("id").ifBlank { newId() },
        name = o.optString("name", DEFAULT_NAME).take(40),
        mapping = HudMapping(componentsFromJson(o.optJSONArray("components") ?: JSONArray())),
    )

    private fun componentsToJson(list: List<MappingComponent>) = JSONArray().apply {
        list.forEach { c ->
            put(
                JSONObject()
                    .put("id", c.id)
                    .put("type", c.type.name)
                    .put("binding", bindingToJson(c.binding))
                    .put("label", c.label)
                    .put("x", c.x.toDouble())
                    .put("y", c.y.toDouble())
                    .put("size", c.size.toDouble())
                    .put("intervalMs", c.intervalMs)
                    .put("sensitivity", c.sensitivity.toDouble())
                    .put("sensitivityY", c.sensitivityY.toDouble())
                    .put("releaseIdle", c.releaseIdle)
                    .put("rapidFire", c.rapidFire)
                    .put("color", c.color)
                    .put("trigger", c.trigger.name)
                    .put("holdMs", c.holdMs)
                    .put("layer", c.layer)
                    .put("targetLayer", c.targetLayer)
                    .put("cycleIds", JSONArray().apply { c.cycleIds.forEach { put(it) } })
                    .put(
                        "dpad",
                        JSONObject()
                            .put("up", bindingToJson(c.dpad.up))
                            .put("left", bindingToJson(c.dpad.left))
                            .put("down", bindingToJson(c.dpad.down))
                            .put("right", bindingToJson(c.dpad.right)),
                    ),
            )
        }
    }

    private fun componentsFromJson(arr: JSONArray): List<MappingComponent> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val type = runCatching { MappingType.valueOf(o.getString("type")) }.getOrDefault(MappingType.Button)
        // Formato antigo guardava keyCode direto no controle.
        val binding = o.optJSONObject("binding")?.let(::bindingFromJson)
            ?: o.optInt("keyCode").takeIf { it != 0 }?.let { InputBinding.key(it) }
            ?: when (type) {
                MappingType.FireButton -> InputBinding.mouse(MouseButton.Left)
                MappingType.MouseUnlock -> InputBinding.key(KeyEvent.KEYCODE_CTRL_LEFT)
                else -> InputBinding.NONE
            }
        // Limpa eventual clique atribuído por engano ao controle de Mira ("View") e corrige Tiro que tenha ficado no botão direito quando o View roubou o esquerdo.
        val safeBinding = when {
            type == MappingType.Aim -> InputBinding.NONE
            type == MappingType.FireButton && binding.mouse == MouseButton.Right && arr.toString().contains("\"type\":\"Aim\"") ->
                InputBinding.mouse(MouseButton.Left)
            else -> binding
        }
        val d = o.optJSONObject("dpad")
        MappingComponent(
            id = o.getInt("id"),
            type = type,
            binding = safeBinding,
            label = o.optString("label").take(24),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
            size = o.optDouble("size", 0.0).toFloat(),
            intervalMs = o.optInt("intervalMs", 80).coerceIn(30, 1000),
            sensitivity = o.optDouble("sensitivity", if (type == MappingType.Dpad) 1.0 else 1.2).toFloat(),
            sensitivityY = o.optDouble("sensitivityY", o.optDouble("sensitivity", 1.2)).toFloat(),
            releaseIdle = o.optBoolean("releaseIdle", true),
            rapidFire = o.optBoolean("rapidFire", false),
            color = o.optLong("color", 0L),
            trigger = runCatching { Trigger.valueOf(o.optString("trigger", "Press")) }.getOrDefault(Trigger.Press),
            holdMs = o.optInt("holdMs", 300),
            layer = o.optInt("layer", 0),
            targetLayer = o.optInt("targetLayer", 1),
            cycleIds = o.optJSONArray("cycleIds")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList(),
            dpad = if (d == null) DpadKeys() else DpadKeys(
                up = d.optJSONObject("up")?.let(::bindingFromJson) ?: DpadKeys().up,
                left = d.optJSONObject("left")?.let(::bindingFromJson) ?: DpadKeys().left,
                down = d.optJSONObject("down")?.let(::bindingFromJson) ?: DpadKeys().down,
                right = d.optJSONObject("right")?.let(::bindingFromJson) ?: DpadKeys().right,
            ),
        )
    }

    private fun bindingToJson(b: InputBinding) = JSONObject()
        .put("key", b.keyCode)
        .put("mods", b.modifiers)
        .put("mouse", b.mouse.name)

    private fun bindingFromJson(o: JSONObject) = InputBinding(
        keyCode = o.optInt("key"),
        modifiers = o.optInt("mods") and InputBinding.MODIFIER_MASK,
        mouse = runCatching { MouseButton.valueOf(o.optString("mouse", "None")) }.getOrDefault(MouseButton.None),
    )

    private fun profilesKey(game: String?) = "profiles:" + (game ?: "_default")

    companion object {
        const val DEFAULT_NAME = "Padrão"
        const val CONFIG_EXT = ".krylogg"
        const val CONFIG_HEADER = "KLYRO-V1:"
        const val CONFIG_SCHEME = "klyro"
        private const val CONFIG_MAGIC = "KLYRO_CFG"
        private const val EXPORT_TAG = "klyro-profile"

        private val HEADERS = listOf(CONFIG_HEADER)
        val SCHEMES = listOf(CONFIG_SCHEME)
        private val TAGS = setOf(EXPORT_TAG)
        private val MAGICS = setOf(CONFIG_MAGIC)
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}
