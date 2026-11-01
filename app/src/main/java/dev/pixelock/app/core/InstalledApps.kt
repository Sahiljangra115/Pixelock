package dev.pixelock.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Launcher apps, without always allowed ones. Main process only, filled when the app opens. */
object InstalledApps {

    private const val ICON_CACHE = 128

    class App(val pkg: String, val label: String, val info: ResolveInfo)

    private val icons = LruCache<String, Drawable>(ICON_CACHE)

    /** The last list read, so the picker can show rows at once. Empty until the first read ends. */
    @Volatile var cached: List<App> = emptyList()
        private set

    /** Labels only. Icons take most of the time, so they load per row as rows appear. */
    suspend fun refresh(context: Context): List<App> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val allowed = DeviceAllowList.resolve(context)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName !in allowed }
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it) }
            .sortedBy { it.label.lowercase() }
            .also { cached = it }
    }

    fun loadedIcon(app: App): Drawable? = icons.get(app.pkg)

    suspend fun icon(context: Context, app: App): Drawable = withContext(Dispatchers.IO) {
        app.info.loadIcon(context.packageManager).also { icons.put(app.pkg, it) }
    }
}
