package com.cyj265.iptvplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动接收器：
 * 监听 BOOT_COMPLETED 广播，用户开启"开机自启动"后，
 * 盒子开机自动启动揽星TV。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 读取用户设置：是否开启开机自启
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            val autoBoot = prefs.getBoolean("auto_boot", false)
            if (autoBoot) {
                // 延迟 3 秒启动，等系统初始化完成
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }, 3000)
            }
        }
    }
}
