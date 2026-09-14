package com.cyj265.iptvplayer

import android.app.Application
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
 */
class App : Application() {

    companion object {
        private const val BUGLY_APP_ID = "9f5599ea70"
    }

    override fun onCreate() {
        super.onCreate()
        if (BUGLY_APP_ID.isNotEmpty()) {
            // 第三个参数：是否开启 debug 模式（true 会打印更多日志，发布版设 false）
            CrashReport.initCrashReport(applicationContext, BUGLY_APP_ID, false)
        }
    }
}
