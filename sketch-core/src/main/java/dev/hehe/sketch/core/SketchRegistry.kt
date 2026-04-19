package dev.hehe.sketch.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object SketchRegistry {
    const val ACTION_SKETCH_ENTRY = "dev.hehe.sketch.action.SKETCH_ENTRY"
    const val META_TITLE = "dev.hehe.sketch.entry.TITLE"
    const val META_SUMMARY = "dev.hehe.sketch.entry.SUMMARY"
    const val META_ORDER = "dev.hehe.sketch.entry.ORDER"
    const val META_MODULE = "dev.hehe.sketch.entry.MODULE"

    fun discover(context: Context): List<SketchEntry> {
        val intent = Intent(ACTION_SKETCH_ENTRY)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setPackage(context.packageName)
        val packageManager = context.packageManager

        return queryEntries(packageManager, intent)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val metadata = activityInfo.metaData
                val title = metadata?.getString(META_TITLE)?.takeIf { it.isNotBlank() }
                    ?: activityInfo.loadLabel(packageManager).toString()

                SketchEntry(
                    title = title,
                    activityClassName = activityInfo.name,
                    activityPackageName = activityInfo.packageName,
                    summary = metadata?.getString(META_SUMMARY)?.takeIf { it.isNotBlank() },
                    order = metadata?.getInt(META_ORDER, Int.MAX_VALUE) ?: Int.MAX_VALUE,
                    moduleName = metadata?.getString(META_MODULE)?.takeIf { it.isNotBlank() }
                        ?: activityInfo.packageName
                )
            }
            .sortedWith(compareBy<SketchEntry> { it.order }.thenBy { it.title.lowercase() })
    }

    fun open(context: Context, entry: SketchEntry) {
        val intent = Intent().setClassName(entry.activityPackageName, entry.activityClassName)
        context.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun queryEntries(
        packageManager: PackageManager,
        intent: Intent
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
    }
}
