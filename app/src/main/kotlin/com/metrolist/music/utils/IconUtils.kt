/**
 * MetroVerse modifications (C) 2026 Rizklee
 * Based on Metrolist and licensed under GPL-3.0.
 */

package com.metrolist.music.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.metrolist.music.R

private const val MANIFEST_PACKAGE = "com.metrolist.music"
private val LEGACY_ALIAS_NAMES = listOf("MainActivityStatic")

enum class AppIcon(
    val aliasName: String,
    @StringRes val labelRes: Int,
    @DrawableRes val previewForegroundRes: Int,
    @DrawableRes val previewBackgroundRes: Int,
    val enabledByDefault: Boolean = false,
) {
    DEFAULT(
        aliasName = "MainActivityAlias",
        labelRes = R.string.app_icon_default,
        previewForegroundRes = R.drawable.ic_launcher_foreground,
        previewBackgroundRes = R.drawable.ic_launcher_background,
        enabledByDefault = true,
    ),
    PIANO_BLACK(
        aliasName = "MainActivityPianoBlack",
        labelRes = R.string.app_icon_piano_black,
        previewForegroundRes = R.drawable.app_icon_piano_black_foreground,
        previewBackgroundRes = R.drawable.app_icon_piano_black_background,
    ),
    ;

    companion object {
        fun fromStoredValue(value: String?): AppIcon =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

object IconUtils {
    @Synchronized
    fun syncWithPreference(
        context: Context,
        storedValue: String?,
    ) = setIcon(context, AppIcon.fromStoredValue(storedValue))

    @Synchronized
    fun setIcon(
        context: Context,
        icon: AppIcon,
    ) {
        val pm = context.packageManager
        val selectedComponent = icon.componentName(context)

        if (!pm.isComponentEnabled(selectedComponent, icon.enabledByDefault)) {
            pm.setComponentEnabledSetting(
                selectedComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        AppIcon.entries.filterNot { it == icon }.forEach { other ->
            val component = other.componentName(context)
            if (pm.isComponentEnabled(component, other.enabledByDefault)) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        LEGACY_ALIAS_NAMES.forEach { aliasName ->
            val component = ComponentName(context, "$MANIFEST_PACKAGE.$aliasName")
            if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    private fun AppIcon.componentName(context: Context) = ComponentName(context, "$MANIFEST_PACKAGE.$aliasName")

    private fun PackageManager.isComponentEnabled(
        component: ComponentName,
        enabledByDefault: Boolean,
    ): Boolean =
        when (getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> enabledByDefault
            else -> false
        }
}
