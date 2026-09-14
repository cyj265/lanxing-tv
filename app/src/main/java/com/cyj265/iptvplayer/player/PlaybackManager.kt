package com.cyj265.iptvplayer.player
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// 【修复缺失导入】解码器健康检测类
import com.cyj265.iptvplayer.player.DecoderHealthCheck

/**
 * 基于 Media3 ExoPlayer 的播放内核（影视仓/TVBox 同款，T1 实测 4K HEVC 流畅）。
 *
 * v1.5.0 曾整体替换为 libVLC（83MB），T1 实测只有 1080P 正常（4K 花屏、
 * 720P 黑屏、卡顿），v1.6.0 回归本方案：单内核 + 解码器三档
 * （自动/仅硬解/仅软解），等效影视仓的 "exo硬解/exo软解"，APK 仅 ~5.8MB。
 *
 * v1.7.1 起支持多线路（频道去重后 sources 列表）：
 * - 线路切换：手动换源（播放界面"换源"按钮 / 设置-线路选择）
 * - 超时自动换源：当前线路在配置的超时秒数内未起播，自动切下一条线路；
 *   播放失败（网络/协议类错误）也自动切下一条线路，全部线路失败才报错。
 * - 解码类错误不切线路（换线路解决不了解码问题），走"检测+提示重启机顶盒"
 *   （自动修复链已按用户要求删除，不再免重启强修 mediaserver）。
 *
 * 自动识别 HLS / 渐进式流，与 TiviMate 同源的内核家族，对标准 HLS 支持最好。
 *
 * 解码策略（设置中可切换）：
 * - auto：硬解优先，解码器初始化失败自动回退其他解码器（默认）；
 * - hardware：只允许硬件解码器；
 * - software：只允许软件解码器（兼容性最好，但 1080p HEVC 较费 CPU）。
 *
 * 直播缓冲：起播 1.5s、重缓冲 3s、持续 15s、上限 45s——秒开且能吸收网络抖动。
 *
 * 失败自动重试：网络瞬时错误自动重播（最多 2 次，仅单线路时生效）。
 */
class PlaybackManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onPlaybackReady(channelName: String)
        fun onPlaybackError(message: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onVideoSizeChanged(width: Int, height: Int)
    }
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentUrl: String? = null
    private var currentChannelName: String? = null
    // 带宽检测器，用于带宽网速估算
    private var bandwidthMeter: BandwidthMeter? = null
    // ---------- 多线路 ----------
    private var currentSources: List<String> = emptyList()
    private var currentSourceIndex = 0
    private var autoTryStartIndex = 0
    private var retryCount = 0
    private var decoderInitRetryCount = 0
    private var renderRetryCount = 0
    /** 当前线路是否曾成功起播（STATE_READY）。用于区分"起播失败"和"播放中途失败" */
    private var hasStartedPlaying = false
    private val retryHandler = Handler(Looper.getMainLooper())
    private val sourceTimeoutHandler = Handler(Looper.getMainLooper())
    private var sourceTimeoutMs: Long = 10_000L
    // ---------- 换台预加载：焦点频道预热 DNS+TCP 连接，OK 键播放时省握手时间 ----------
    private val preloadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var preloadUrl: String? = null
    @Volatile private var preloadThread: Thread? = null
    /** 当前频道是否已从硬解自动降级到软解（每个频道重置一次） */
    private var degradedToSoftware = false
    /** 解码方式：auto / hardware / software */
    private var decoderMode: String = run {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        // v1.3.0 的 bug：硬解失败自动降级会把 decoder_mode 持久化成 "software"，
        // 导致后续所有频道都被迫软解（1080p 卡、4K 只出声不出画）。
        // 升级后一次性重置回 "auto"，避免用户被旧 bug 留下的设置污染。
        if (!prefs.getBoolean("decoder_migrated_v57", false)) {
            if (prefs.getString("decoder_mode", "auto") == "software") {
                prefs.edit().putString("decoder_mode", "auto").apply()
            }
            prefs.edit().putBoolean("decoder_migrated_v57", true).apply()
        }
        prefs.getString("decoder_mode", "auto") ?: "auto"
    }
    /** 应用启动时同步一次超时秒数偏好（避免每个频道都读 SP） */
    fun setSourceTimeoutMs(timeoutMs: Long) {
        sourceTimeoutMs = if (timeoutMs > 0) timeoutMs else 10_000L
    }

    /**
     * 换台预加载：焦点移到某频道时调用，后台预热该频道第一条线路的 DNS+TCP 连接。
     * OK 键播放时复用已建立的连接，省去 DNS 解析和 TCP 握手，换台快 0.2~0.5s。
     * 节流：300ms 内重复调用只执行最后一次；只预加载 HTTP/HTTPS 流。
     */
    fun preloadChannel(sources: List<String>) {
        val url = sources.firstOrNull() ?: return
        if (!url.startsWith("http")) return
        if (url == preloadUrl) return  // 同一频道不重复预加载
        preloadUrl = url
        // 节流：延迟 300ms 执行，快速移动焦点时只预加载最后停留的频道
        preloadHandler.removeCallbacksAndMessages(null)
        preloadHandler.postDelayed({
            val target = preloadUrl ?: return@postDelayed
            // 中断旧的预加载线程
            preloadThread?.interrupt()
            preloadThread = Thread({
                try {
                    val conn = java.net.URL(target).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "HEAD"
                    conn.connect()
                    // 只读取响应头即可预热连接，不下载内容
                    conn.responseCode
                    conn.disconnect()
                } catch (ignored: Exception) {
                    // 预加载失败不影响播放，静默忽略
                }
            }, "preload-$target").apply { isDaemon = true }
            preloadThread?.start()
        }, 300)
    }
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // 起播成功：取消超时换源
                hasStartedPlaying = true
                sourceTimeoutHandler.removeCallbacksAndMessages(null)
            }
            listener.onPlaybackStateChanged(player?.isPlaying == true)
        }
        override fun onPlayerError(error: PlaybackException) {
            // 诊断日志：丰富的上下文信息写入 crash.log（设置→调试 可查看，便于真机定位问题）
            writeCrashLog(error)
            val url = currentUrl
            val isDecoderError =
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
            // 多线路 + 非解码类错误：
            // - 起播失败（从未 STATE_READY）：自动切下一条线路（换线路通常能解决）
            // - 播放中途失败（曾成功起播）：直接报错停止，避免"黑屏→切线路→再黑屏"反复循环
            if (!isDecoderError && currentSources.size > 1) {
                if (hasStartedPlaying) {
                    retryCount = 0
                    retryHandler.removeCallbacksAndMessages(null)
                    listener.onPlaybackError("播放中断（线路 ${currentSourceIndex + 1}），请手动换线路或重新打开")
                    return
                }
                autoFail("线路 ${currentSourceIndex + 1} 播放失败")
                return
            }
            // ===== 解码类错误：先自动重试一次硬解（瞬时资源冲突），再降级软解 =====
            if (isDecoderError) {
                // v1.14.3：解码初始化失败先自动重试一次（延迟500ms）。
                // N1(Amlogic)快速退出重开时旧 MediaCodec 未释放导致 configure 瞬时失败，
                // 重试一次通常能恢复（用户实测第二次/第三次启动能正常播放）。
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    && decoderInitRetryCount < 1 && url != null) {
                    decoderInitRetryCount++
                    val name = currentChannelName
                    listener.onPlaybackError("解码器初始化失败，500ms 后自动重试…")
                    retryHandler.postDelayed({ retryPlay(url, name ?: url) }, 500)
                    return
                }
                // v1.14.4：解码失败（DECODING_FAILED）自动重试 2 次（延迟800ms）。
                // 启动时 Surface 未就绪导致视频渲染失败（有声音黑屏），
                // 重试几次后 Surface 就绪即可正常播放（用户实测双击OK键能进入）。
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                    && renderRetryCount < 2 && url != null) {
                    renderRetryCount++
                    val name = currentChannelName
                    listener.onPlaybackError("视频渲染失败，${renderRetryCount}/2 自动重试…")
                    retryHandler.postDelayed({ retryPlay(url, name ?: url) }, 800)
                    return
                }
                // 已降级过软解仍失败：说明软解也放不了（N1 软解 4K HEVC 撑不住），
                // 重试只会"黑屏→重载→再黑屏"循环，直接停止并给出明确提示。
                if (degradedToSoftware) {
                    retryCount = 0
                    retryHandler.removeCallbacksAndMessages(null)
                    listener.onPlaybackError("解码失败：硬解/软解均无法播放，请尝试更换清晰度或重启机顶盒")
                    return
                }
                // 未降级过：auto 模式下降级软解一次（仅当解码器健康检测通过/提示后）
                if (decoderMode == "auto" && url != null) {
                    degradedToSoftware = true // 防重复进入检测流程
                    val name = currentChannelName
                    listener.onPlaybackError("硬解失败，正在检测解码器状态…")
                    Thread {
                        val broken = !DecoderHealthCheck.isHardwareHevcHealthy()
                        Handler(Looper.getMainLooper()).post {
                            listener.onPlaybackError(
                                if (broken) "解码器异常，请重启机顶盒后重试（已临时切换软解）"
                                else "硬解失败，已切换软件解码"
                            )
                            retryHandler.postDelayed({ degradeToSoftware(url, name ?: url) }, 800)
                        }
                    }.start()
                    return
                }
                // 仅硬解/仅软解模式下解码失败：不重试，直接报错
                retryCount = 0
                retryHandler.removeCallbacksAndMessages(null)
                listener.onPlaybackError("解码失败（${error.errorCodeName}），请尝试切换解码方式或重启机顶盒")
                return
            }
            // ===== 非解码类错误（网络/协议）：最多自动重试 2 次，间隔递增（1.5s / 3s） =====
            if (retryCount < 2 && url != null) {
                retryCount++
                val delay = 1500L * retryCount
                val name = currentChannelName
                listener.onPlaybackError("播放失败，${retryCount} 秒后自动重试…")
                retryHandler.postDelayed({ retryPlay(url, name ?: url) }, delay)
                return
            }
            retryCount = 0
            retryHandler.removeCallbacksAndMessages(null)
            listener.onPlaybackError(error.errorCodeName)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener.onPlaybackStateChanged(isPlaying)
        }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                listener.onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        }
    }
    fun attach(playerView: PlayerView) {
        if (player != null) return
        this.playerView = playerView
        playerView.player = buildPlayer()
        player = playerView.player as ExoPlayer
        player?.addListener(playerListener)
    }
    /** 切换解码方式：需要重建播放器（renderer 在创建时固定），保留当前频道继续播放。 */
    fun applyDecoderMode(mode: String) {
        if (mode == decoderMode) return
        decoderMode = mode
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .edit().putString("decoder_mode", mode).apply()
        val pv = playerView ?: return
        val name = currentChannelName
        player?.removeListener(playerListener)
        player?.release()
        pv.player = null
        player = buildPlayer()
        pv.player = player
        player?.addListener(playerListener)
        if (currentSources.isNotEmpty()) {
            retryCount = 0
            playCurrentSource()
        }
        // 修复：currentSources 为空时不调用 play(name, name)——name 是频道名不是 URL，
        // 必然播放失败。此时只重建播放器即可，等用户下次选台时再播。
    }
    fun currentDecoderMode(): String = decoderMode
    /**
     * 硬解解码器初始化/解码失败时，把播放器重建为软件解码模式续播（影视仓同款兜底）。
     * 单独抽成方法：避免在 playerListener 初始化期间被 lambda 引用自身，
     * 触发 Kotlin "Type checking has run into a recursive problem"。
     *
     * 注意：只对当前频道生效（degradedToSoftware 内存标记），不写入全局设置，
     * 否则会把后续所有频道都拖进软解（1080p 卡顿、4K 只有声音没图像）。
     */
    private fun degradeToSoftware(url: String, channelName: String) {
        val pv = playerView ?: return
        player?.removeListener(playerListener)
        player?.release()
        pv.player = null
        degradedToSoftware = true
        player = buildPlayer()
        pv.player = player
        player?.addListener(playerListener)
        retryCount = 0
        // 手动续播（不调用 play()，避免 play() 重置 degradedToSoftware 标记）
        currentUrl = url
        currentChannelName = channelName
        val p = player ?: return
        p.setMediaSource(buildMediaSource(url, channelName))
        p.prepare()
        p.playWhenReady = true
        // 修复：不在 prepare 后立即回调 onPlaybackReady（播放器还未 STATE_READY）。
        // 频道名更新由 playCurrentSource 统一处理，真正就绪由 playerListener 回调。
    }
    @OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        // 每次重建播放器都新建带宽检测器实例
        val meter = DefaultBandwidthMeter.Builder(context).build()
        bandwidthMeter = meter
        // 直播缓冲：起播 1.5s、卡顿后 3s、持续目标 8s、上限 15s。
        // 之前起播缓冲 15s 需要攒够两三个 TS 分片才开播，导致"等待播放时间太长"。
        // 直播流（TS 10s 分片）缓冲越小起播越快、延迟越低；15s 上限足够吸收网络抖动。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                8_000,    // minBufferMs
                15_000,   // maxBufferMs
                800,      // bufferForPlaybackMs：起播所需缓冲（秒开，从1.5s降到0.8s加速换台）
                3_000     // bufferForPlaybackAfterRebufferMs：卡顿后恢复所需缓冲
            )
            .build()
        val renderersFactory = DefaultRenderersFactory(context)
        // 重要：不强制禁用异步 MediaCodec 队列。
        // 斐讯 T1（S912/Android 7）上影视仓 EXO 硬解 4K HEVC 都能流畅，
        // 用的就是 Media3 默认 async 配置；我们上一版 forceDisable 异步队列后，
        // 1.8.0 表现为解码器 init failed、1.4.1 表现为只出声不出画（硬解
        // configure 成功但 OMX 组件不吐帧）。恢复默认异步队列与影视仓一致。
        when {
            degradedToSoftware -> {
                // 本频道硬解失败后的软解兜底：只影响当前频道，切台/重启恢复设置的模式
                renderersFactory.setEnableDecoderFallback(false)
                renderersFactory.setMediaCodecSelector(SoftwareOnlySelector)
            }
            decoderMode == "hardware" -> {
                // 仅硬件解码：硬解失败直接报错，不回退软解（避免软解 1080p 卡死）
                renderersFactory.setEnableDecoderFallback(false)
                renderersFactory.setMediaCodecSelector(HardwareOnlySelector)
            }
            decoderMode == "software" -> {
                // 仅软件解码：绕过一切硬件解码器
                renderersFactory.setEnableDecoderFallback(false)
                renderersFactory.setMediaCodecSelector(SoftwareOnlySelector)
            }
            else -> {
                // 自动：硬解优先，失败自动回退
                renderersFactory.setEnableDecoderFallback(true)
            }
        }
        // 强制选择最高码率/最高分辨率轨道（TVBox 系播放器同款做法）：
        // HLS 多码率流默认按带宽估计选 variant，软解/网络抖动时会被"降级"到
        // 低分辨率（如 4K 变 720×576）。固定最高档，保证分辨率不缩水。
        //
        // setExceedRendererCapabilitiesIfNecessary：Amlogic 解码器能力上报不全
        // （MediaCodecInfo 把 4K 判为不支持），默认会因此降档选 1080P/720P，
        // 这里强制超出上报能力选择，让解码器实际去解（硬解本身支持 4K）。
        // ★ v1.6.1 曾一度移除该参数，实测与 v1.4.0 行为对比后确认：v1.4.0（带此参数）
        // 在用户 T1 上播放正常，v62 黑屏属解码器坏状态（重启 T1 可清除），
        // 故 v1.6.2 恢复与 v1.4.0 完全一致的配置。
        val trackSelector = DefaultTrackSelector(context)
        trackSelector.setParameters(
            DefaultTrackSelector.Parameters.Builder()
                .setForceHighestSupportedBitrate(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .build()
        )
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(meter)
            .build()
    }
    /** 只保留非软件解码器（硬件/系统专用解码器）。仅过滤视频解码器： */
    private object HardwareOnlySelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            // 音频不过滤：T1 的 AAC 只有软件解码器（OMX.google.aac.decoder），
            // 若一并过滤则硬解模式下没有声音（用户实测"仅硬解只有图像没有声音"）。
            // 音频始终放行全部解码器，由系统自行选择（软解 AAC 音质/功耗无差别）。
            if (!mimeType.startsWith("video/")) return all
            return all.filter { !it.softwareOnly }
        }
    }
    /** 只保留软件解码器（兼容性最好）。仅过滤视频解码器，音频放行全部。 */
    private object SoftwareOnlySelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            if (!mimeType.startsWith("video/")) return all
            return all.filter { it.softwareOnly }
        }
    }
    /** 画面比例：fit / fill / zoom / 16:9 / 4:3 */
    fun setAspectRatio(mode: String) {
        val pv = playerView ?: return
        when (mode) {
            "fill" -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "16:9", "4:3" -> {
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                try {
                    val frame = pv.findViewById<AspectRatioFrameLayout>(
                        androidx.media3.ui.R.id.exo_content_frame
                    )
                    frame?.setAspectRatio(if (mode == "16:9") 16f / 9f else 4f / 3f)
                } catch (ignored: Exception) {
                }
            }
            else -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }
    // ---------- 多线路播放 ----------
    /** 播放频道（单线路兼容入口） */
    fun play(url: String, channelName: String) {
        play(listOf(url), channelName)
    }
    /** 播放频道（多线路：sources 按顺序排列，失败/超时自动切换） */
    fun play(sources: List<String>, channelName: String) {
        if (sources.isEmpty()) {
            listener.onPlaybackError("该频道没有可用线路")
            return
        }
        currentSources = sources.toList()
        currentSourceIndex = 0
        autoTryStartIndex = 0
        retryCount = 0
        decoderInitRetryCount = 0
        renderRetryCount = 0
        degradedToSoftware = false
        playCurrentSource()
    }
    private fun playCurrentSource() {
        val p = player ?: return
        val url = currentSources.getOrNull(currentSourceIndex) ?: return
        hasStartedPlaying = false
        currentUrl = url
        currentChannelName = currentChannelName ?: url
        // 换台优化：setMediaSource 自动替换当前 source，无需手动 stop/clear，减少播放器重置开销
        p.setMediaSource(buildMediaSource(url, currentChannelName ?: url))
        p.prepare()
        p.playWhenReady = true
        listener.onPlaybackReady(currentChannelName ?: url)
        startSourceTimeout()
    }
    /** 手动切换下一条线路（允许循环回绕），返回是否有多线路 */
    fun switchToNextLine(): Boolean {
        if (currentSources.size <= 1) return false
        currentSourceIndex = (currentSourceIndex + 1) % currentSources.size
        // 修复：手动切换后重置自动换源起始位置，避免 autoFail 提前判定"全部失败"
        autoTryStartIndex = currentSourceIndex
        playCurrentSource()
        android.widget.Toast.makeText(context.applicationContext, "已切换到线路 ${currentSourceIndex + 1}/${currentSources.size}", android.widget.Toast.LENGTH_SHORT).show()
        return true
    }

    /** 手动切换上一条线路（允许循环回绕），返回是否有多线路 */
    fun switchToPrevLine(): Boolean {
        if (currentSources.size <= 1) return false
        currentSourceIndex = (currentSourceIndex - 1 + currentSources.size) % currentSources.size
        autoTryStartIndex = currentSourceIndex
        playCurrentSource()
        android.widget.Toast.makeText(context.applicationContext, "已切换到线路 ${currentSourceIndex + 1}/${currentSources.size}", android.widget.Toast.LENGTH_SHORT).show()
        return true
    }
    /** 自动失败换源：一圈全部失败则报错停止 */
    private fun autoFail(reason: String) {
        if (currentSources.size <= 1) {
            listener.onPlaybackError(reason)
            return
        }
        val next = (currentSourceIndex + 1) % currentSources.size
        if (next == autoTryStartIndex) {
            sourceTimeoutHandler.removeCallbacksAndMessages(null)
            listener.onPlaybackError("所有线路均播放失败，请检查网络或手动换源")
            return
        }
        currentSourceIndex = next
        playCurrentSource()
        listener.onPlaybackError("$reason，自动切换线路 ${currentSourceIndex + 1}/${currentSources.size}")
    }
    private fun startSourceTimeout() {
        sourceTimeoutHandler.removeCallbacksAndMessages(null)
        sourceTimeoutHandler.postDelayed({
            val p = player ?: return@postDelayed
            if (p.playbackState == Player.STATE_READY || p.isPlaying) return@postDelayed
            if (currentSources.size > 1) {
                autoFail("线路 ${currentSourceIndex + 1} 起播超时（${sourceTimeoutMs / 1000}s）")
            }
            // 单线路超时：不做任何动作，让播放器自行缓冲/报错
        }, sourceTimeoutMs)
    }
    /** 当前线路序号（0 起） */
    fun currentSourceIndex(): Int = currentSourceIndex
    /** 当前频道线路总数 */
    fun sourceCount(): Int = currentSources.size
    /** 当前线路地址 */
    fun currentSourceUrl(): String? = currentUrl

    /** 播放器是否正在播放（用于 onResume 时判断状态） */
    fun isPlaying(): Boolean {
        val p = player ?: return false
        return try {
            p.isPlaying
        } catch (e: Exception) {
            false
        }
    }

    /** onResume 时恢复播放：如果播放器已就绪但被暂停，就恢复播放 */
    fun resumeIfPaused() {
        val p = player ?: return
        try {
            if (!p.isPlaying && p.playbackState == androidx.media3.common.Player.STATE_READY) {
                p.play()
            }
        } catch (e: Exception) {
        }
    }

    /** 当前估计带宽（kbps），用于顶部"显示网速" */
    @OptIn(UnstableApi::class)
    fun bandwidthKbps(): Long {
        return try {
            val localMeter = bandwidthMeter
            return if (localMeter != null) {
                localMeter.getBitrateEstimate() / 1000L
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun retryPlay(url: String, channelName: String) {
        val p = player ?: return
        hasStartedPlaying = false
        p.stop()
        p.clearMediaItems()
        p.setMediaSource(buildMediaSource(url, channelName))
        p.prepare()
        p.playWhenReady = true
        // 修复：不在 prepare 后立即回调 onPlaybackReady（播放器还未 STATE_READY）。
        // 频道名更新由 playCurrentSource 统一处理，真正就绪由 playerListener 回调。
    }
    /**
     * 按内容类型显式构建媒体源：
     * .m3u8 → HlsMediaSource（带容错配置）；其余 → ProgressiveMediaSource。
     * 比默认推断更稳，避免个别源被误判容器。
     */
    @OptIn(UnstableApi::class)
    private fun buildMediaSource(url: String, channelName: String): androidx.media3.exoplayer.source.MediaSource {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayer/IPTVPlayer")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(channelName)
            .build()
        // 使用 DefaultMediaSourceFactory：内部对 HLS/DASH/SS 等流媒体有更好的默认容错处理，
        // 包括 HLS 时间戳调整器初始化、分段边界处理等，比手动创建 HlsMediaSource 更稳定。
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
        return mediaSourceFactory.createMediaSource(mediaItem)
    }
    fun togglePlayPause() {
        val p = player ?: return
        if (p.playWhenReady) {
            p.pause()
        } else {
            p.playWhenReady = true
        }
    }
    /**
     * 丰富的崩溃日志记录：包含设备信息、播放状态、视频格式、网络、内存等，
     * 便于真机定位解码/渲染/网络问题。
     */
    private fun writeCrashLog(error: PlaybackException) {
        try {
            val sb = StringBuilder()
            sb.append("\n========== 播放错误 ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())).append(" ==========\n")
            try {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                sb.append("[App] versionName=").append(pi.versionName).append(" versionCode=").append(pi.versionCode).append("\n")
            } catch (ignored: Exception) {}
            sb.append("[Device] model=").append(Build.MODEL)
                .append(" brand=").append(Build.BRAND)
                .append(" android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT)
                .append("\n")
            sb.append("[Playback] channel=").append(currentChannelName ?: "null")
                .append(" decoderMode=").append(decoderMode)
                .append(" degraded=").append(degradedToSoftware)
                .append(" retry=").append(retryCount)
                .append(" initRetry=").append(decoderInitRetryCount)
                .append(" renderRetry=").append(renderRetryCount)
                .append(" started=").append(hasStartedPlaying)
                .append(" src=").append(currentSourceIndex).append("/").append(currentSources.size)
                .append("\n")
            try {
                val p = player
                if (p != null) {
                    sb.append("[Player] state=").append(p.playbackState)
                        .append(" playing=").append(p.isPlaying)
                        .append(" loading=").append(p.isLoading)
                        .append(" pos=").append(p.currentPosition)
                        .append(" buffered=").append(p.bufferedPosition)
                        .append("\n")
                    val vf = p.videoFormat
                    if (vf != null) {
                        sb.append("[Video] mime=").append(vf.sampleMimeType)
                            .append(" ").append(vf.width).append("x").append(vf.height)
                            .append(" fps=").append(vf.frameRate)
                            .append("\n")
                    }
                }
            } catch (ignored: Exception) {}
            try {
                // 兼容各版本 Media3：不访问具体字段，直接输出异常类型和消息
                val cause = error.cause
                if (cause != null) {
                    sb.append("[Decoder] type=").append(cause.javaClass.simpleName)
                        .append(" msg=").append(cause.message ?: cause.toString())
                        .append("\n")
                }
            } catch (ignored: Exception) {}
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val ni = cm.activeNetworkInfo
                sb.append("[Network] connected=").append(ni?.isConnected ?: false)
                    .append(" type=").append(ni?.typeName ?: "null")
                    .append("\n")
            } catch (ignored: Exception) {}
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val rt = Runtime.getRuntime()
                sb.append("[Memory] avail=").append(mi.availMem / 1024 / 1024).append("MB")
                    .append(" total=").append(mi.totalMem / 1024 / 1024).append("MB")
                    .append(" low=").append(mi.lowMemory)
                    .append(" heap=").append((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024).append("MB")
                    .append("\n")
            } catch (ignored: Exception) {}
            sb.append("[Error] code=").append(error.errorCodeName)
                .append(" msg=").append(error.message ?: "null")
                .append("\n")
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            sb.append("[Stack]\n").append(sw.toString()).append("\n")
            sb.append("========== 结束 ==========\n")
            java.io.File(context.filesDir, "app.log").appendText(sb.toString())
        } catch (ignored: Exception) {}
    }

    fun release() {
        retryHandler.removeCallbacksAndMessages(null)
        sourceTimeoutHandler.removeCallbacksAndMessages(null)
        preloadHandler.removeCallbacksAndMessages(null)
        preloadThread?.interrupt()
        preloadThread = null
        // 修复：显式移除 listener，避免 player.release() 过程中回调已销毁的 Activity
        player?.removeListener(playerListener)
        // v1.14.3：先 stop 再 release，确保 MediaCodec 停止后再释放，
        // 避免 N1(Amlogic)快速退出重开时旧解码器未释放导致新 configure 失败。
        try {
            player?.stop()
        } catch (ignored: Exception) {
        }
        player?.release()
        player = null
        bandwidthMeter = null
        playerView = null
    }
}