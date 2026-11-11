package com.cyj265.iptvplayer

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.tencent.bugly.crashreport.CrashReport
import java.security.MessageDigest
import java.util.Locale

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
 * - 附加字段仅含官方构建号与官方发布证书指纹，不含任何用户隐私数据
 * - 符合 Bugly 4.1.9.x 最新监管要求
 */
class App : Application() {

    companion object {
        // AppID 由 build.gradle.kts 从 local.properties / 环境变量注入，不再硬编码到源码
        private val BUGLY_APP_ID = BuildConfig.BUGLY_APP_ID

        /**
         * 官方发布证书的完整 SHA-256 指纹（证书 DER，等价于 keytool -printcert 的 SHA256）。
         * 与揽星视频 dev-5.6.1 的 ReleaseTrust.PINNED 同一套做法：
         * - 这是公开信息（任何人都能从已发布 APK 读出），不进 secrets；
         * - 写成数组，轮换 keystore 时先把新指纹追加进来发一版，下一版再移除旧的，
         *   避免只留一枚时换了证书就再也收不到崩溃数据。
         *
         * 来源：v290 正式包 META-INF/CERT.RSA（CN=LanxingTV），
         *   openssl x509 -outform DER | openssl dgst -sha256
         *   20F81610C1F687870C0D3263152D5507363599AACD9569A357DDBC94ED8D8F93
         *
         * 改包者能读到这个常量，但没有官方私钥就无法生成相同证书，重签后指纹必然不同。
         * 校验不通过只跳过 Bugly 初始化，App 任何功能都不受影响。
         */
        private val OFFICIAL_CERT_PINS = arrayOf(
            "20F81610C1F687870C0D3263152D5507363599AACD9569A357DDBC94ED8D8F93"
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BUGLY_APP_ID.isEmpty()) return

        // 公开分发后存在第三方改包：它们沿用本包名与 AppID，崩溃也会汇总到同一个
        // Bugly 产品，且常因自行注入的解码扩展版本不匹配而报错。
        // 只有官方证书签名的包才初始化上报；取不到签名或任何异常一律拒绝（fail closed），
        // 不让「漏配 / 读取失败」退化成「不校验」的虚假安全感。
        val cert = officialCertDigest() ?: return
        initBugly(cert)
    }

    /**
     * 返回命中的官方证书完整 SHA-256（大写 hex），签名不符或读取失败返回 null。
     * 与揽星视频一致：Android 9+ 用 GET_SIGNING_CERTIFICATES，旧版用 GET_SIGNATURES；
     * 任一 signer 命中 pin 即视为官方包。
     */
    private fun officialCertDigest(): String? = try {
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
        if (signers.isNullOrEmpty()) {
            null
        } else {
            val pins = OFFICIAL_CERT_PINS.map { normalize(it) }
            signers
                .mapNotNull { sha256Hex(it.toByteArray()) }
                .firstOrNull { it in pins }
        }
    } catch (e: Exception) {
        null
    }

    /** 证书 DER 的 SHA-256，完整 64 位大写 hex；失败返回 null。 */
    private fun sha256Hex(bytes: ByteArray): String? = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789ABCDEF"
        buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xff
                append(hex[v ushr 4])
                append(hex[v and 0x0f])
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 去冒号与空白、转大写，使 keytool 输出与纯 hex 可互比。 */
    private fun normalize(value: String): String =
        value.replace(":", "").filter { !it.isWhitespace() }.uppercase(Locale.ROOT)

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
                // 带上构建号：同一 versionName 下的每个 CI 构建在 Bugly 里独立成版本，
                // 否则 #291/#292… 全部聚合到「1.16.6」，趋势图无法定位是哪个构建引入的问题。
                appVersion = "${BuildConfig.VERSION_NAME}.${BuildConfig.BUILD_NUMBER}"
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
}
