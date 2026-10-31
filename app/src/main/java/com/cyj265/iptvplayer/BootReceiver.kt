package com.cyj265.iptvplayer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 开机自启动接收器：
 * 监听 BOOT_COMPLETED 广播，用户开启"开机自启动"后，
 * 盒子开机自动启动揽星TV。
 *
 * Android 10+ 起系统禁止后台进程直接拉起 Activity，BOOT_COMPLETED 不在豁免范围，
 * 直接 startActivity 会被静默拦截。因此在 Q 及以上改为发一条通知让用户按键打开，
 * 同时仍尝试一次直接启动（部分电视盒子 ROM 未启用该限制）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 读取用户设置：是否开启开机自启
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_boot", false)) return

        // 延迟 3 秒启动，等系统初始化完成
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName) ?: return@postDelayed
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    postLaunchNotification(context, launchIntent)
                }
                context.startActivity(launchIntent)
            } catch (ignored: Throwable) {
                // startActivity 被系统拦截时通知已在前面发出，无需额外处理
            }
        }, 3000)
    }

    /** Android 10+ 的后台启动兜底：发通知，用户按 OK 打开应用。 */
    private fun postLaunchNotification(context: Context, launchIntent: Intent) {
        try {
            // Android 13+ 需要通知权限；未授权时只能靠上面的直接启动
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "开机启动", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "开机后提醒打开揽星TV" }
                nm.createNotificationChannel(channel)
            }
            val pi = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle("揽星TV 已就绪")
                .setContentText("按 OK 打开继续观看")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        const val CHANNEL_ID = "boot_launch"
        const val NOTIFICATION_ID = 1001
    }
}
