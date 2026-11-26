package gg.klyro.mapper.game

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Um jogo (app instalado) escolhido para usar com o mapeamento. */
data class Game(
    val packageName: String,
    val name: String,
    val versionName: String?,
    val icon: Drawable?,
) {
    companion object {
        fun from(pm: PackageManager, info: ApplicationInfo): Game = Game(
            packageName = info.packageName,
            name = pm.getApplicationLabel(info).toString(),
            versionName = runCatching { pm.getPackageInfo(info.packageName, 0).versionName }.getOrNull(),
            icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
        )

        /** Carrega pelo nome do pacote; null se o app não está mais instalado. */
        fun load(pm: PackageManager, packageName: String): Game? =
            runCatching { from(pm, pm.getApplicationInfo(packageName, 0)) }.getOrNull()
    }
}
