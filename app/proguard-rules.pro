# Add project specific ProGuard rules here.

# ===== Media3 / ExoPlayer =====
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ===== ExoPlayer 解码器/渲染器（反射创建）=====
-keep class * extends androidx.media3.exoplayer.Renderer { *; }
-keep class * implements androidx.media3.exoplayer.Renderer { *; }
-keep class androidx.media3.exoplayer.mediacodec.** { *; }
-keep class androidx.media3.decoder.** { *; }

# ===== HLS 数据源 =====
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.datasource.** { *; }

# ===== nanohttpd（HTTP 服务反射）=====
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ===== zxing（二维码）=====
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ===== Bugly 崩溃上报 =====
-keep class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.crashreport.CrashReport { *; }

# ===== Kotlin 协程 =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ===== 应用自身类（防止反射/序列化被混淆）=====
-keep class com.cyj265.iptvplayer.** { *; }
