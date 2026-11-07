package me.capcom.smsgateway.helpers

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Mnaseb SIM: opens the maker's "autostart / run in background" screen.
 *
 * The component names are INFERRED from public reports and drift by ROM version;
 * every start is guarded and the app-info page is always the last resort.
 */
object OemAutostart {
    /** Which screen [open] ended up showing. */
    enum class Opened { OEM_SCREEN, APP_INFO, NONE }

    /** (package, activity class) pairs, pure data so it is testable on the JVM. */
    data class Target(val pkg: String, val cls: String)

    private val HUAWEI = listOf(
        Target(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        Target(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity"
        ),
    )
    private val XIAOMI = listOf(
        Target(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
    )
    private val SAMSUNG = listOf(
        Target(
            "com.samsung.android.lool",
            "com.samsung.android.sm.battery.ui.BatteryActivity"
        ),
    )
    private val OPPO = listOf(
        Target(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
    )
    private val VIVO = listOf(
        Target(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
    )
    private val ONEPLUS = listOf(
        Target(
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
        ),
    )

    /** Maker families the guide has dedicated step text for. */
    enum class Maker { HUAWEI, XIAOMI, SAMSUNG, OPPO, VIVO, ONEPLUS, OTHER }

    fun makerOf(manufacturer: String?): Maker =
        when (manufacturer?.trim()?.lowercase()) {
            "huawei", "honor" -> Maker.HUAWEI
            "xiaomi", "redmi", "poco" -> Maker.XIAOMI
            "samsung" -> Maker.SAMSUNG
            "oppo", "realme" -> Maker.OPPO
            "vivo" -> Maker.VIVO
            "oneplus" -> Maker.ONEPLUS
            else -> Maker.OTHER
        }

    /** Ordered OEM screens to try for [manufacturer]; empty for an unknown maker. */
    fun targets(manufacturer: String?): List<Target> =
        when (makerOf(manufacturer)) {
            Maker.HUAWEI -> HUAWEI
            Maker.XIAOMI -> XIAOMI
            Maker.SAMSUNG -> SAMSUNG
            Maker.OPPO -> OPPO
            Maker.VIVO -> VIVO
            Maker.ONEPLUS -> ONEPLUS
            Maker.OTHER -> emptyList()
        }

    /** OEM components in order; the app-info fallback is added by [open]. */
    fun candidates(manufacturer: String?): List<ComponentName> =
        targets(manufacturer).map { ComponentName(it.pkg, it.cls) }

    fun open(context: Context, manufacturer: String? = android.os.Build.MANUFACTURER): Opened {
        for (component in candidates(manufacturer)) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) == null) continue
                context.startActivity(intent)
                return Opened.OEM_SCREEN
            } catch (e: ActivityNotFoundException) {
                // try the next candidate
            } catch (e: SecurityException) {
                // the system manager may refuse a direct start; try the next candidate
            }
        }
        val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(info)
            Opened.APP_INFO
        } catch (e: ActivityNotFoundException) {
            Opened.NONE
        } catch (e: SecurityException) {
            Opened.NONE
        }
    }
}
