plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cyj265.iptvplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cyj265.iptvplayer"
        minSdk = 23
        targetSdk = 34
        versionCode = 69
        versionName = "1.16.1"

        // 构建号：GitHub Actions 传入 RUN_NUMBER，本地构建默认 0
        buildConfigField("int", "BUILD_NUMBER", (System.getenv("RUN_NUMBER") ?: "0").toString())

        // Bugly AppID 不硬编码到源码：避免 fork 复用作者 Bugly 账户、污染崩溃数据。
        // 优先级：local.properties > 环境变量 BUGLY_APP_ID > 空（空则不上报）。
        // 注意：不能用 java.util.Properties —— Gradle Kotlin DSL 里 java 被解析为
        // JavaPluginExtension 导致 Unresolved reference，故手动解析键值对。
        val buglyAppId = run {
            val f = rootProject.file("local.properties")
            val fromFile = if (f.exists()) {
                f.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                    .map { it.split("=", limit = 2) }
                    .firstOrNull { it[0].trim() == "BUGLY_APP_ID" }
                    ?.get(1)?.trim()
            } else null
            // 两级都要求非空：避免某一侧存在但值为空时把另一侧覆盖掉
            val fromEnv = System.getenv("BUGLY_APP_ID")?.trim()
            fromFile?.takeIf { it.isNotEmpty() } ?: fromEnv?.takeIf { it.isNotEmpty() } ?: ""
        }
        // 只打印长度与来源，不打印 App ID 本身；CI 日志里一眼能看出 secret 有没有生效
        println(
            "[bugly] BUGLY_APP_ID " + if (buglyAppId.isEmpty()) {
                "未注入（本次构建不会启用崩溃上报）"
            } else {
                "已注入，长度=${buglyAppId.length}"
            }
        )
        buildConfigField("String", "BUGLY_APP_ID", "\"$buglyAppId\"")
    }

    signingConfigs {
        create("release") {
            // CI 构建时通过环境变量注入：SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD /
            // SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD。PKCS#12 格式（.p12）。
            val storeFileProp = System.getenv("SIGNING_STORE_FILE") ?: ""
            println("[signing] SIGNING_STORE_FILE=$storeFileProp")
            if (storeFileProp.isNotEmpty() && file(storeFileProp).exists()) {
                storeFile = file(storeFileProp)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
                storeType = "PKCS12"
                println("[signing] release keystore configured: ${file(storeFileProp).absolutePath}")
            } else {
                println("[signing] WARNING: release keystore not found, signing will fail")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

}

dependencies {
    // core-ktx 保持 1.13.1：1.15+ 起要求 AGP 9.1.0 / compileSdk 37，
    // 升级需同步跳 AGP 9.x + Gradle 9.x，留到构建链专项升级时一起做。
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    // lifecycle 保持 2.8.4：2.11.0 的 lint 检测器（NonNullableMutableLiveDataDetector）
    // 依赖新版 Kotlin 分析 API，与 AGP 8.7.3 内置 lint 运行时不兼容，
    // 会在 lintVitalAnalyzeRelease 抛 IncompatibleClassChangeError 导致构建失败。
    // 本应用未使用 LiveData，待 AGP 升级到 8.13+ 后再一并提升。
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Media3 (ExoPlayer) 播放内核
    // 1.11.1（1.11.0 的补丁版）：用于 HLS/H.265 时间戳容错修复。
    // 历史：1.4.1 曾长期固定（影视仓同代内核，T1 兼容性最好）；
    // 1.8.0 曾出现 HEVC 硬解 DECODER_INIT_FAILED，故回退 1.4.1。
    // 本次升级目标：解决甘肃移动 IPTV H.265 HLS 流 SampleQueue.commitSample
    // 时间戳非单调递增导致的播放失败（4K/极清频道）。
    // 注：v1.5.0 曾尝试 libVLC 3.5.1 内核（83MB），T1 实测仅 1080P 正常、
    // 4K 花屏/720P 黑屏，且体积过大，已回退纯 EXO 方案。
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // 扫码局域网管理：轻量 HTTP 服务 + 二维码编码（体积小，无额外权限）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.4")

    // Bugly 崩溃自动上报（崩溃自动上传到 bugly.qq.com，无需用户手动导出日志）
    // AppID 在 App.kt 中配置，为空则不上报
    implementation("com.tencent.bugly:crashreport:4.1.9.3")
}
