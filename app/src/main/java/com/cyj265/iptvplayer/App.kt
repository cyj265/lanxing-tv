package com.cyj265.iptvplayer

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.tencent.bugly.crashreport.CrashReport
import java.security.MessageDigest

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
 * - 附加字段仅含官方构建号与本应用安装包证书摘要，不含任何用户隐私数据
 * - 符合 Bugly 4.1.9.x 最新监管要求
 */
class App : Application() {

    companion object {
        // AppID 由 build.gradle.kts 从 local.properties / 环境变量注入，不再硬编码到源码
        private val BUGLY_APP_ID = BuildConfig.BUGLY_APP_ID

        /**
         * 官方签名证书摘要：证书 DER 的 SHA-256 前 4 字节（与 [certificateDigest] 同算法）。
         *
         * 取值方式（CI 的 release keystore，CN=LanxingTV）：
         *   keytool -printcert -jarfile iptv-player-v289.apk
         *   SHA256: 20:F8:16:10:C1:F6:...  → 20f81610
         *
         * 这不是密钥，只是白名单：改包者能读到它，但没有官方私钥就无法生成相同证书，
         * 重签名后摘要必然不同。相比之下 AppID / AppKey 都是打包进 APK 的客户端常量，
         * 公开分发后反编译即可原样复用，无法用来区分构建来源。
         *
         * 注意：更换 release keystore 时必须同步更新此值。最坏后果只是官方包不上报
         * 崩溃数据，App 的任何功能都不受影响（校验不通过仅跳过 Bugly 初始化）。
         */
        private const val OFFICIAL_CERT_DIGEST = "20f81610"
    }

    override fun onCreate() {
        super.onCreate()
        if (BUGLY_APP_ID.isEmpty()) return

        val cert = certificateDigest()
        // 公开分发后存在第三方改包：它们沿用本包名与 AppID（甚至 AppKey），崩溃也会
        // 汇总到同一个 Bugly 产品，且常因自行注入的解码扩展版本不匹配而报错。
        // 签名不符就直接不初始化上报，从源头减少这类干扰数据。
        // Debug 构建与证书读取失败时一律放行，避免影响本地开发和真机调试。
        if (!BuildConfig.DEBUG && cert != "unknown" &&
            !cert.equals(OFFICIAL_CERT_DIGEST, ignoreCase = true)
        ) {
            return
        }
        initBugly(cert)
    }

    private fun initBugly(cert: String) {
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

            // 即便改包主动绕过上面的校验，也能在 Bugly 详情页「自定义数据」里通过
            // cert 一眼分辨来源；build 用于区分官方构建号。
            CrashReport.putUserData(applicationContext, "build", BuildConfig.BUILD_NUMBER.toString())
            CrashReport.putUserData(applicationContext, "cert", cert)
        } catch (e: Exception) {
            // 初始化失败不影响 App 正常运行
            e.printStackTrace()
        }
    }

    /** 本安装包签名证书 SHA-256 的前 4 字节（8 位十六进制），仅用于区分构建来源。 */
    private fun certificateDigest(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        @Suppress("DEPRECATION")
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val raw = signers?.firstOrNull()?.toByteArray()
        if (raw == null) {
            "unknown"
        } else {
            MessageDigest.getInstance("SHA-256").digest(raw)
                .take(4)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    } catch (e: Exception) {
        "unknown"
    }
}
