package com.cyj265.iptvplayer

import android.app.Application
import android.provider.Settings
import com.tencent.bugly.crashreport.CrashReport

/**
 * 应用入口：初始化 Bugly 崩溃自动上报。
 *
 * Bugly AppID 获取方式：
 * 1. 访问 https://bugly.qq.com 注册/登录
 * 2. 创建产品（平台选 Android，产品名随意）
 * 3. 获取 AppID（一串数字），替换下方 BUGLY_APP_ID
 *
 * 崩溃会自动上报到 Bugly 后台，无需用户手动导出日志。
 * 如需关闭上报，将 BUGLY_APP_ID 设为空字符串即可。
 *
 * 合规说明：
 * - 使用 UserStrategy 显式配置设备 ID（Android ID），不自动获取 IMEI 等敏感信息
 * - 不采集用户隐私数据，仅上报崩溃堆栈和设备基本信息
 * - 符合 Bugly 4.1.9.x 最新监管要求
 */
class App : Application() {

    companion object {
        private const val BUGLY_APP_ID = "9f5599ea70"
    }

    override fun onCreate() {
        super.onCreate()
        if (BUGLY_APP_ID.isNotEmpty()) {
            initBugly()
        }
    }

    private fun initBugly() {
        try {
            // 使用 Android ID 作为设备标识，避免获取 IMEI 等敏感信息
            val deviceId = try {
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

            // 显式配置 UserStrategy，符合最新监管要求
            val strategy = CrashReport.UserStrategy(applicationContext).apply {
                this.deviceID = deviceId
                appChannel = "github"
                appVersion = BuildConfig.VERSION_NAME
                appPackageName = packageName
            }

            // 第三个参数：是否开启 debug 模式（true 会打印更多日志，发布版设 false）
            CrashReport.initCrashReport(applicationContext, BUGLY_APP_ID, false, strategy)
        } catch (e: Exception) {
            // 初始化失败不影响 App 正常运行
            e.printStackTrace()
        }
    }
}
