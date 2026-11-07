package com.example.devicesync

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Locale
import java.util.concurrent.TimeUnit

object KeepAliveGuide {

    fun launchAll(context: Context) {
        if (!isIgnoringBattery(context)) {
            context.startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val auto = resolveAutoStart(context)
        context.startActivity(auto ?: Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isIgnoringBattery(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    private fun resolveAutoStart(context: Context): Intent? {
        val m = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val list = buildList {
            if (m.contains("xiaomi") || m.contains("redmi"))
                add(c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            if (m.contains("huawei") || m.contains("honor")) {
                add(c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                add(c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            }
            if (m.contains("oppo") || m.contains("realme")) {
                add(c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                add(c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            }
            if (m.contains("vivo") || m.contains("iqoo")) {
                add(c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                add(c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            }
            if (m.contains("samsung"))
                add(c("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity"))
            if (m.contains("oneplus"))
                add(c("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            if (m.contains("meizu"))
                add(c("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))
        }
        return list.firstOrNull { context.packageManager.resolveActivity(it, 0) != null }
    }

    private fun c(pkg: String, cls: String) = Intent()
        .setComponent(ComponentName(pkg, cls))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun enqueueWatchdog(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ContextCompat.startForegroundService(
                context, Intent(context, SyncNodeService::class.java)
            )
        }
    }
}

class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val am = applicationContext.getSystemService(ActivityManager::class.java)
        val running = am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == SyncNodeService::class.java.name
        }
        if (!running) {
            ContextCompat.startForegroundService(
                applicationContext, Intent(applicationContext, SyncNodeService::class.java)
            )
        }
        return Result.success()
    }
}
