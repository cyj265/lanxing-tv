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
        versionCode = 67
        versionName = "1.15.3"
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

    // 构建号：GitHub Actions 传入 RUN_NUMBER，本地构建默认 0
    // 用于更新检测：即使版本号相同，只要构建号增加就提示更新
    defaultConfig {
        buildConfigField("int", "BUILD_NUMBER", (System.getenv("RUN_NUMBER") ?: "0").toString())

        // Bugly AppID 不硬编码到源码：避免 fork 复用作者 Bugly 账户、污染崩溃数据。
        // 优先级：local.properties > 环境变量 BUGLY_APP_ID > 空（空则不上报）。
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
            fromFile ?: System.getenv("BUGGLY_APP_ID") ?: ""
        }
        buildConfigField("String", "BUGGLY_APP_ID", "\"$buglyAppId\"")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Media3 (ExoPlayer) 播放内核
    // 1.11.0（2026-08 稳定版）：升级以获取 HLS/H.265 时间戳容错修复。
    // 历史：1.4.1 曾长期固定（影视仓同代内核，T1 兼容性最好）；
    // 1.8.0 曾出现 HEVC 硬解 DECODER_INIT_FAILED，故回退 1.4.1。
    // 本次升级目标：解决甘肃移动 IPTV H.265 HLS 流 SampleQueue.commitSample
    // 时间戳非单调递增导致的播放失败（4K/极清频道）。
    // 注：v1.5.0 曾尝试 libVLC 3.5.1 内核（83MB），T1 实测仅 1080P 正常、
    // 4K 花屏/720P 黑屏，且体积过大，已回退纯 EXO 方案。
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    // 扫码局域网管理：轻量 HTTP 服务 + 二维码编码（体积小，无额外权限）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    // Bugly 崩溃自动上报（崩溃自动上传到 bugly.qq.com，无需用户手动导出日志）
    // AppID 在 App.kt 中配置，为空则不上报
    implementation("com.tencent.bugly:crashreport:4.1.9.3")
}