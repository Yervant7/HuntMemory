/*
 * HuntMemory - Process Memory Editor & Scanner for Android
 * Copyright (C) 2026 Yervant7
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yervant.huntmem.ui.compat

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

enum class OemRomType(val displayName: String) {
    XIAOMI_HYPEROS("Xiaomi HyperOS"),
    XIAOMI_MIUI("Xiaomi MIUI"),
    OPPO_COLOROS("OPPO / Realme ColorOS"),
    VIVO_ORIGINOS("Vivo OriginOS / Funtouch"),
    HUAWEI_EMUI("Huawei EMUI / HarmonyOS"),
    SAMSUNG_ONEUI("Samsung One UI"),
    GENERIC_AOSP("Android AOSP / Standard")
}

object OemCompatibilityHelper {
    private const val TAG = "OemCompatibilityHelper"

    // Xiaomi MIUI / HyperOS AppOps custom codes
    const val OP_SYSTEM_ALERT_WINDOW = 24
    const val OP_AUTO_START = 10008
    const val OP_SHOW_WHEN_LOCKED = 10020
    const val OP_BACKGROUND_START_ACTIVITY = 10021

    val currentRomType: OemRomType by lazy { detectOemRom() }

    fun isXiaomi(): Boolean {
        return currentRomType == OemRomType.XIAOMI_HYPEROS || currentRomType == OemRomType.XIAOMI_MIUI
    }

    fun isHyperOs(): Boolean {
        return currentRomType == OemRomType.XIAOMI_HYPEROS
    }

    fun isMiui(): Boolean {
        return currentRomType == OemRomType.XIAOMI_MIUI
    }

    fun isCustomOem(): Boolean {
        return currentRomType != OemRomType.GENERIC_AOSP
    }

    private fun detectOemRom(): OemRomType {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()

        val hyperOsProp = getSystemProperty("ro.mi.os.version.name")
        if (hyperOsProp.isNotEmpty()) {
            return OemRomType.XIAOMI_HYPEROS
        }

        val miuiProp = getSystemProperty("ro.miui.ui.version.name")
        val miuiCode = getSystemProperty("ro.miui.ui.version.code")
        if (miuiProp.isNotEmpty() || miuiCode.isNotEmpty() ||
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
            brand.contains("redmi") || brand.contains("poco")
        ) {
            // Check if newer Xiaomi ROM identifies as HyperOS
            val miuiVer = getSystemProperty("ro.build.version.incremental")
            return if (hyperOsProp.isNotEmpty() || miuiVer.contains("OS", ignoreCase = true)) {
                OemRomType.XIAOMI_HYPEROS
            } else {
                OemRomType.XIAOMI_MIUI
            }
        }

        val oppoProp = getSystemProperty("ro.build.version.opporom")
        if (oppoProp.isNotEmpty() || manufacturer.contains("oppo") ||
            manufacturer.contains("realme") || manufacturer.contains("oneplus") ||
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus")
        ) {
            return OemRomType.OPPO_COLOROS
        }

        val vivoProp = getSystemProperty("ro.vivo.os.version")
        if (vivoProp.isNotEmpty() || manufacturer.contains("vivo") ||
            manufacturer.contains("iqoo") || brand.contains("vivo") || brand.contains("iqoo")
        ) {
            return OemRomType.VIVO_ORIGINOS
        }

        val emuiProp = getSystemProperty("ro.build.version.emui")
        val harmonyProp = getSystemProperty("hw_sc.build.platform.version")
        if (emuiProp.isNotEmpty() || harmonyProp.isNotEmpty() ||
            manufacturer.contains("huawei") || manufacturer.contains("honor") ||
            brand.contains("huawei") || brand.contains("honor")
        ) {
            return OemRomType.HUAWEI_EMUI
        }

        if (manufacturer.contains("samsung") || brand.contains("samsung")) {
            return OemRomType.SAMSUNG_ONEUI
        }

        return OemRomType.GENERIC_AOSP
    }

    /**
     * Checks if standard Android overlay permission is granted (Settings.canDrawOverlays).
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Checks if background pop-up windows permission is granted on Xiaomi / HyperOS / MIUI.
     * Checks OP_BACKGROUND_START_ACTIVITY (10021) and OP_SYSTEM_ALERT_WINDOW (24).
     */
    fun hasPopupPermission(context: Context): Boolean {
        if (!isXiaomi()) return true
        val bgStartAllowed = checkAppOp(context, OP_BACKGROUND_START_ACTIVITY)
        val alertWindowAllowed = checkAppOp(context, OP_SYSTEM_ALERT_WINDOW)
        return bgStartAllowed && alertWindowAllowed
    }

    /**
     * Checks if autostart permission is granted on Xiaomi / HyperOS / MIUI.
     * Checks OP_AUTO_START (10008).
     */
    fun hasAutostartPermission(context: Context): Boolean {
        if (!isXiaomi()) return true
        return checkAppOp(context, OP_AUTO_START)
    }

    /**
     * Reflectively checks an AppOps opCode without throwing exceptions.
     */
    @SuppressLint("DiscouragedPrivateApi")
    fun checkAppOp(context: Context, opCode: Int): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        return try {
            val method = try {
                AppOpsManager::class.java.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
            } catch (_: Throwable) {
                AppOpsManager::class.java.getMethod(
                    "unsafeCheckOpRawNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
            }
            val mode = method.invoke(appOps, opCode, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) {
            Log.d(TAG, "checkAppOp($opCode) reflection fallback: ${e.message}")
            true
        }
    }

    /**
     * Opens the most direct OEM-specific overlay / floating window permission screen.
     */
    fun openOverlayPermissionSettings(context: Context): Boolean {
        val pkg = context.packageName

        val intentsToTry = when (currentRomType) {
            OemRomType.XIAOMI_HYPEROS, OemRomType.XIAOMI_MIUI -> listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    putExtra("extra_pkgname", pkg)
                },
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                    putExtra("extra_pkgname", pkg)
                },
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", pkg)
                },
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri())
            )

            OemRomType.OPPO_COLOROS -> listOf(
                Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity")
                    putExtra("package_name", pkg)
                },
                Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity")
                },
                Intent().apply {
                    component = ComponentName("com.oppo.safe", "com.oppo.safe.permission.PermissionTopActivity")
                },
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri())
            )

            OemRomType.VIVO_ORIGINOS -> listOf(
                Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                    putExtra("packagename", pkg)
                },
                Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
                },
                Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.FloatWindowManager")
                },
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri())
            )

            OemRomType.HUAWEI_EMUI -> listOf(
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity")
                },
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.addviewmonitor.AddViewMonitorActivity")
                },
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri())
            )

            OemRomType.SAMSUNG_ONEUI, OemRomType.GENERIC_AOSP -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri()),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            )
        }

        // Fallbacks for any failure
        val fallbackIntents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$pkg".toUri()),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())
        )

        for (intent in intentsToTry + fallbackIntents) {
            if (tryLaunchIntent(context, intent)) {
                return true
            }
        }
        return false
    }

    /**
     * Opens the OEM-specific Autostart management screen to prevent background task killers
     * from abruptly killing HuntMemory overlay services and root connections.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val pkg = context.packageName

        val intentsToTry = when (currentRomType) {
            OemRomType.XIAOMI_HYPEROS, OemRomType.XIAOMI_MIUI -> listOf(
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                },
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartMainActivity")
                }
            )

            OemRomType.OPPO_COLOROS -> listOf(
                Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                },
                Intent().apply {
                    component = ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
                }
            )

            OemRomType.VIVO_ORIGINOS -> listOf(
                Intent().apply {
                    component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                },
                Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                }
            )

            OemRomType.HUAWEI_EMUI -> listOf(
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                },
                Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
                }
            )

            OemRomType.SAMSUNG_ONEUI, OemRomType.GENERIC_AOSP -> listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())
            )
        }

        val fallbackIntents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())
        )

        for (intent in intentsToTry + fallbackIntents) {
            if (tryLaunchIntent(context, intent)) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether battery optimizations are ignored for this application.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Requests the system / OEM power manager to ignore battery optimizations for HuntMemory.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val pkg = context.packageName

        if (isXiaomi()) {
            val miuiBatteryIntent = Intent().apply {
                component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                putExtra("package_name", pkg)
                putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
            }
            if (tryLaunchIntent(context, miuiBatteryIntent)) {
                return true
            }
        }

        val intentsToTry = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$pkg".toUri()
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())
        )

        for (intent in intentsToTry) {
            if (tryLaunchIntent(context, intent)) {
                return true
            }
        }
        return false
    }

    private fun tryLaunchIntent(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pm = context.packageManager
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved.isNotEmpty() || intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.d(TAG, "tryLaunchIntent failed for ${intent.component ?: intent.action}: ${e.message}")
            false
        }
    }

    @SuppressLint("PrivateApi")
    fun getSystemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            (getMethod.invoke(null, key, "") as? String).orEmpty().trim()
        } catch (_: Throwable) {
            ""
        }
    }
}
