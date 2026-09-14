package com.cyj265.iptvplayer

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cyj265.iptvplayer.data.Channel
import com.cyj265.iptvplayer.data.EpgParser
import com.cyj265.iptvplayer.data.EpgProgram
import com.cyj265.iptvplayer.data.HttpLoader
import com.cyj265.iptvplayer.data.LanRemoteServer
import com.cyj265.iptvplayer.data.PlaylistParser
import com.cyj265.iptvplayer.data.PlaylistRepository
import com.cyj265.iptvplayer.data.SourceHealthChecker
import com.cyj265.iptvplayer.databinding.ActivityMainBinding
import com.cyj265.iptvplayer.player.PlaybackManager
import com.cyj265.iptvplayer.ui.ChannelAdapter
import com.cyj265.iptvplayer.ui.EpgListAdapter
import com.cyj265.iptvplayer.util.QrCodeUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：全屏播放 + 悬浮频道列表（OK 唤出，二级分组 + 源切换）+ 右侧设置面板（菜单键唤出）。
 * 遥控器：上下键换台、OK 频道列表、菜单键设置、CH+/CH- 换台。
 *
 * v1.7.1：
 * - 频道同名多源自动合并为一条（如本地列表 CCTV1综合 ×7），播放失败/超时自动切换线路，
 *   播放界面底部"换源"按钮手动切换，设置-线路选择同步切换；
 * - 超时换源：线路起播超时秒数可选 5/10/15/20/25/30/60（默认 10），超时自动切下一条线路；
 * - 偏好设置：显示时间 / 显示网速 / 换台反转 / 跨选分类；
 * - 退出直播：结束全部进程，不再有后台声音；
 * - EPG 节目指南支持每行一个、多个地址，全部加载合并；
 * - 删除自动修复链（不再免重启强修 mediaserver），解码异常仅提示重启机顶盒；
 * - 崩溃日志导出入口同时放在 调试 与 关于 分区。
 * 注意：播放/解码路径（Media3 配置、三档解码策略）继续冻结，不再改动。
 */
class MainActivity : AppCompatActivity(), PlaybackManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: PlaylistRepository
    private val sourceHealthChecker = SourceHealthChecker()
    private lateinit var playback: PlaybackManager
    private lateinit var adapter: ChannelAdapter
    private val epgListAdapter = EpgListAdapter()
    private var remoteServer: LanRemoteServer? = null

    private var allChannels: List<Channel> = emptyList()
    private lateinit var groupAdapter: GroupAdapter
    private var currentGroup: String? = null  // null = 全部频道
    /** 分组选中后待定位的频道行位置（右键进频道列表时使用） */
    private var pendingChannelScrollPos = -1
    private var lastBackPressTime: Long = 0
    private var autoUpdateCheck: Boolean = true
    private var favorites: MutableSet<String> = HashSet()
    private var showFavoritesOnly = false
    private var currentChannel: Channel? = null
    private var epgPrograms: Map<String, List<EpgProgram>> = emptyMap()
    /** EPG 加载状态：区分"未配置/加载中/就绪/失败"四种提示 */
    private enum class EpgLoadState { NOT_CONFIGURED, LOADING, READY, FAILED }
    private var epgLoadState = EpgLoadState.NOT_CONFIGURED
    private var epgLoadedCount = 0
    /** 复用时间格式化器，避免 EPG 刷新时每频道 new 两次 SimpleDateFormat（GC 压力） */
    private val epgTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var lastEpgAttemptAt = 0L

    private var videoW = 0
    private var videoH = 0
    private var currentSettingsTab = 0

    // 覆盖层自动隐藏：4 秒无操作淡出顶部信息条与底部控制条
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { hideOverlay() }

    // 右上角时钟 + 每秒刷新网速显示，并每分钟刷新一次列表 EPG 节目文本
    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockTick = 0
    private val clockRunnable = object : Runnable {
        override fun run() {
            try {
                val sb = StringBuilder(
                    SimpleDateFormat("yyyy/MM/dd\nHH:mm:ss", Locale.getDefault()).format(Date())
                )
                if (repository.showSpeed) {
                    val kbps = playback.bandwidthKbps()
                    if (kbps > 0) sb.append("\n网速 ").append(kbps / 1000.0).append(" Mbps")
                }
                binding.tvClock.text = sb.toString()
                clockTick++
                // 每秒更新 EPG 进度条（只更新进度，不更新文字）
                updateEpgProgressOnly()
                if (clockTick % 300 == 0 && epgPrograms.isNotEmpty()) {
                    adapter.epgNow = buildEpgNowMap()
                    adapter.epgNext = buildEpgNextMap()
                    updateProgramInfo()
                }
            } catch (ignored: Throwable) {
            }
            clockHandler.postDelayed(this, 1000L)
        }
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importLocalFile(it) }
        }

    private val createLogDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { exportCrashLogTo(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        try {
            initApp()
        } catch (t: Throwable) {
            reportStartupCrash(t)
        }
    }

    /** 启动主体：任何异常都会走 reportStartupCrash 弹窗显示，不再静默闪退。 */
    private fun initApp() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        repository = PlaylistRepository(this)
        favorites = repository.getFavorites().toMutableSet()

        playback = PlaybackManager(this, this)
        playback.attach(binding.playerView)
        playback.setAspectRatio(repository.aspectRatio)
        playback.setSourceTimeoutMs(repository.switchTimeoutSec * 1000L)

        // v1.14.3：禁用启动时解码器健康检测。
        // 根因：启动时后台线程创建 HEVC 解码器实例，和主线程 resumeLastChannel()
        // 播放器初始化冲突，导致 OMX 服务状态异常，第一次启动解码器 init 失败黑屏。
        // 播放失败时 onPlayerError 中已会调用 DecoderHealthCheck 检测，启动时无需重复检测。
        // checkDecoderHealthOnStart()

        adapter = ChannelAdapter(
            onChannelClick = { channel -> onChannelClick(channel) },
            onCollapsedChanged = { groups -> repository.saveCollapsedGroups(groups) },
            onChannelFocused = { ch ->
                lastFocusedChannel = ch
                updateProgramInfo(ch)
                // 换台预加载：焦点频道预热 DNS+TCP，OK 键播放时省握手时间
                playback.preloadChannel(ch.sources.ifEmpty { listOf(ch.url) })
                // 焦点移动只更新节目信息，不自动换台（OK 键才播放）
            }
        )
        adapter.favorites = favorites
        // 从未手动折叠过时默认全部收起（二级分组体验）
        adapter.setDefaultCollapsed(!repository.hasCollapsedPrefs())
        adapter.setCollapsedGroups(repository.getCollapsedGroups())
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter
        // 第三栏：今日节目单列表
        binding.epgListView.layoutManager = LinearLayoutManager(this)
        binding.epgListView.adapter = epgListAdapter
        // 双栏模式：右侧纯频道列表（带序号），不显示分组头
        adapter.showGroupHeaders = false

        // 左侧分组列表
        groupAdapter = GroupAdapter(
            onGroupClick = { _ ->
                // 分组上按 OK：直接进入频道列表（分组已在焦点移动时切换）
                binding.channelList.requestFocus()
            },
            onGroupFocused = { group ->
                // 焦点移动到分组即切换右侧频道列表（不抢焦点、不换台）
                // 注意：不能调用 groupAdapter.setSelected()，否则 notifyDataSetChanged 会重置焦点导致跳位/卡死
                currentGroup = group
                rememberLastGroup(group ?: "")
                applyFilter()
                // 记录目标位置，右键进入右栏时才定位
                val targetPos = currentChannel
                    ?.let { adapter.positionOfChannel(it.id) }
                    ?.takeIf { it >= 0 }
                    ?: adapter.firstPositionOfGroup(group ?: "")
                pendingChannelScrollPos = targetPos
                if (targetPos >= 0) {
                    adapter.channelAt(targetPos)?.let { updateProgramInfo(it) }
                }
            }
        )
        binding.groupList.layoutManager = LinearLayoutManager(this)
        binding.groupList.adapter = groupAdapter

        setupSearch()
        setupButtons()
        setupSettingsPanel()
        setupSourceBar()
        startRemoteServer()

        // 启动：先显示缓存，再尝试刷新
        val cached = repository.loadLocalChannels() ?: repository.loadCachedChannels(
            repository.getActiveSource().orEmpty()
        )
        if (!cached.isNullOrEmpty()) {
            onChannelsLoaded(cached)
        }
        if (!repository.getActiveSource().isNullOrEmpty()) {
            reloadPlaylist()
        }
        loadEpgIfConfigured()

        // 自动恢复上次频道（v1.14.5：等待 SurfaceView 的 surfaceCreated 回调后再播放，
        // 避免启动时视频解码器init成功但渲染到未就绪Surface导致黑屏有声音+native崩溃闪退）
        if (repository.autoResume) {
            waitForSurfaceAndResume()
        }

        // 恢复上次分组
        try {
            if (rememberGroupPrefs().getBoolean("remember_group", false)) {
                val lastGroup = rememberGroupPrefs().getString("last_group", null)
                if (lastGroup != null && allChannels.any { it.group == lastGroup }) {
                    currentGroup = lastGroup
                    groupAdapter.setSelected(lastGroup)
                    applyFilter()
                }
            }
        } catch (ignored: Throwable) {
        }

        // 启动时显示覆盖层，随后自动淡出
        showOverlay()
        updateSourceBar()
        updateSourceStatus()
        updateClockVisibility()

        // 启动自动检查更新（可在设置-关于里开关）
        autoUpdateCheck = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_update_check", true)
        if (autoUpdateCheck) {
            checkUpdateSilent()
        }
    }

    /** 启动失败：记录日志并弹窗显示堆栈。 */
    private fun reportStartupCrash(t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            crashFile().writeText(
                "startup=" + System.currentTimeMillis() + "\n" + sw.toString(),
                Charsets.UTF_8
            )
        } catch (ignored: Exception) {
        }
        try {
            AlertDialog.Builder(this)
                .setTitle("启动失败（已记录日志）")
                .setMessage(
                    "请把此弹窗截图发给开发者，或在设置→调试中查看完整日志：\n\n" +
                        Log.getStackTraceString(t)
                )
                .setPositiveButton("知道了", null)
                .setCancelable(false)
                .show()
        } catch (ignored: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        // 切后台再回来时，如果播放器已就绪但被暂停，恢复播放（避免重新初始化）
        try {
            playback.resumeIfPaused()
        } catch (ignored: Throwable) {
        }
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onPause()
    }

    // ---------- 运行日志（启动/播放/问题定位用） ----------

    private fun crashFile(): File = File(filesDir, "app.log")

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile().writeText(
                    "time=" + System.currentTimeMillis() + "\nthread=" + thread.name +
                        "\n" + sw.toString(),
                    Charsets.UTF_8
                )
                // 新崩溃：重置"已提示"标志，下次启动会再次提示
                getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putBoolean("crash_prompted", false).apply()
            } catch (ignored: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 启动自检：后台探测 HEVC 硬解解码器好坏（不阻塞 UI，仅提示不修复）。 */
    private fun checkDecoderHealthOnStart() {
        Thread {
            val healthy = com.cyj265.iptvplayer.player.DecoderHealthCheck.isHardwareHevcHealthy()
            Handler(Looper.getMainLooper()).post {
                if (!healthy && !isFinishing) {
                    Toast.makeText(
                        applicationContext,
                        "检测到硬解解码器异常：如播放黑屏/无声，请重启机顶盒",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun showLastCrashIfAny() {
        val f = crashFile()
        if (!f.exists()) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // 每条新崩溃只提示一次：日志保留，供调试区导出/清除
        if (prefs.getBoolean("crash_prompted", false)) return
        val log = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return
        }
        prefs.edit().putBoolean("crash_prompted", true).apply()
        val brief = log.lineSequence().take(6).joinToString("\n")
        runOnUiThread {
            Toast.makeText(
                applicationContext,
                "运行日志（可在 设置-调试 中导出）：\n$brief",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- 退出（结束全部进程） ----------

    private fun confirmExit() {
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.nav_exit)
                .setMessage(R.string.exit_confirm)
                .setPositiveButton(R.string.nav_exit) { _, _ -> exitApp() }
                .setNegativeButton(R.string.close, null)
                .show()
        } catch (ignored: Throwable) {
            exitApp()
        }
    }

    private fun exitApp() {
        overlayHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        try {
            remoteServer?.stop()
        } catch (ignored: Exception) {
        }
        try {
            try { sourceHealthChecker.shutdown() } catch (ignored: Throwable) {}
        playback.release()
        } catch (ignored: Exception) {
        }
        try {
            finishAffinity()
        } catch (ignored: Exception) {
        }
        try {
            Process.killProcess(Process.myPid())
        } catch (ignored: Exception) {
        }
        try {
            System.exit(0)
        } catch (ignored: Exception) {
        }
    }

    // ---------- 覆盖层自动隐藏 ----------

    private fun showOverlay() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
        val bar = binding.nowPlayingBar
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.animate().cancel()
            bar.alpha = 0f
            bar.animate().alpha(1f).setDuration(200).start()
        }
        overlayHandler.removeCallbacks(overlayHideRunnable)
        if (repository.autoHideOverlay) {
            overlayHandler.postDelayed(overlayHideRunnable, 4000L)
        }
    }

    private fun hideOverlay() {
        val bar = binding.nowPlayingBar
        if (bar.visibility == View.VISIBLE) {
            bar.animate().cancel()
            bar.animate().alpha(0f).setDuration(300)
                .withEndAction { bar.visibility = View.GONE }
        }
    }

    // ---------- 面板显隐 ----------

    private val isChannelPanelVisible: Boolean
        get() = binding.channelPanel.visibility == View.VISIBLE

    private val isSettingsPanelVisible: Boolean
        get() = binding.settingsPanel.visibility == View.VISIBLE

    private fun showChannelPanel() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
        binding.settingsPanel.visibility = View.GONE
        binding.channelPanel.visibility = View.VISIBLE
        binding.channelPanel.alpha = 0f
        binding.channelPanel.animate().alpha(1f).setDuration(160).start()
        updateSourceBar()
        updateProgramInfo()
        // 若 EPG 已配置但之前加载失败，打开列表时自动重试（网络恢复后无需重启）
        if (epgLoadState == EpgLoadState.FAILED && repository.getEpgUrls().isNotEmpty()) {
            loadEpgIfConfigured()
        }
        // 默认焦点到左侧分组列表的当前分组
        currentChannel?.group?.let { g ->
            if (currentGroup != g) {
                currentGroup = g
                groupAdapter.setSelected(g)
                applyFilter()
            }
        }
        val groupPos = groupAdapter.positionOfGroup(currentGroup)
        if (groupPos >= 0) {
            binding.groupList.scrollToPosition(groupPos)
        }
        // 记录右栏目标位置（当前播放频道或分组第一个）
        val curPos = currentChannel?.let { adapter.positionOfChannel(it.id) } ?: -1
        pendingChannelScrollPos = if (curPos >= 0) curPos else adapter.firstPositionOfGroup(currentGroup ?: "")
        binding.groupList.post {
            if (groupPos >= 0) {
                val holder = binding.groupList.findViewHolderForAdapterPosition(groupPos)
                holder?.itemView?.requestFocus()
            }
            if (!binding.groupList.hasFocus()) binding.groupList.requestFocus()
        }
    }

    private fun hideChannelPanel() {
        binding.channelPanel.visibility = View.GONE
        showOverlay()
    }

    private fun showSettingsPanel() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
        binding.channelPanel.visibility = View.GONE
        binding.settingsPanel.visibility = View.VISIBLE
        binding.settingsPanel.alpha = 0f
        binding.settingsPanel.animate().alpha(1f).setDuration(160).start()
        refreshSettingsSourceInput()
        updateCurrentSourceLabel()
        updateSourceStatus()
        refreshCrashLog()
        updateSourceOptions()
        updateLineupLabel()
        updateTimeoutSelection()
        updateDecoderSelection()
        updateAspectRatioSelection()
        // 焦点默认落在导航列当前项（上下键切换分区）；延迟到面板布局完成后，
        // 避免 updateSourceOptions() 重建直播源列表导致焦点被抢进二级菜单。
        val navs = settingsNavs()
        val navIdx = currentSettingsTab.coerceIn(0, navs.size - 1)
        binding.settingsPanel.post { navs[navIdx].requestFocus() }
    }

    private fun hideSettingsPanel() {
        binding.settingsPanel.visibility = View.GONE
        showOverlay()
    }

    // ---------- UI 初始化 ----------

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
    }

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener { showSettingsPanel() }
        binding.btnList.setOnClickListener { showChannelPanel() }
        binding.btnSwitchLine.setOnClickListener { switchToNextLine() }
        binding.btnFavorites.setOnClickListener {
            showFavoritesOnly = !showFavoritesOnly
            applyFilter()
            binding.tvStatus.text =
                if (showFavoritesOnly) getString(R.string.favorites) else getString(R.string.channel_list)
        }
        binding.btnPrev.setOnClickListener { switchChannel(-1) }
        binding.btnNext.setOnClickListener { switchChannel(1) }
        binding.btnFavoriteCurrent.setOnClickListener { toggleFavoriteCurrent() }
    }

    // ---------- 线路选择 ----------

    /** 手动切换当前频道下一条线路（多线路频道有效） */
    private fun switchToNextLine() {
        val switched = playback.switchToNextLine()
        if (!switched) {
            Toast.makeText(applicationContext, "当前频道只有 1 条线路", Toast.LENGTH_SHORT).show()
            return
        }
        updateLineupLabel()
        updateNowPlaying()
    }

    /** 手动切换当前频道上一条线路（多线路频道有效） */
    private fun switchToPrevLine() {
        val switched = playback.switchToPrevLine()
        if (!switched) {
            Toast.makeText(applicationContext, "当前频道只有 1 条线路", Toast.LENGTH_SHORT).show()
            return
        }
        updateLineupLabel()
        updateNowPlaying()
    }

    private fun updateLineupLabel() {
        try {
            val count = playback.sourceCount()
            binding.tvBtnSwitchLine.text =
                if (count > 1) {
                    getString(R.string.line_fmt, playback.currentSourceIndex() + 1, count)
                } else {
                    "线路 1/1"
                }
        } catch (ignored: Throwable) {
        }
    }

    // ---------- 直播源切换条（频道列表顶部） ----------

    private fun setupSourceBar() {
        binding.tvSourceBar.setOnClickListener { switchToNextSource() }
    }

    /** 设置面板：动态渲染直播源切换列表（当前源高亮，点击切换，右侧删除） */
    private fun updateSourceOptions() {
        try {
            binding.sourceListContainer.removeAllViews()
            val sources = repository.getSources()
            if (sources.isEmpty()) {
                val tv = TextView(this).apply {
                    text = "暂无直播源，请在下方添加（长按可删除）"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    textSize = 13f
                    setPadding(16, 12, 8, 12)
                }
                binding.sourceListContainer.addView(tv)
                return
            }
            val active = repository.activeSourceIndex
            sources.forEachIndexed { i, url ->
                // 每行：横向 LinearLayout（左侧源信息可点击切换 + 右侧删除按钮）
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 4, 0, 4)
                }
                val health = repository.getSourceHealth(url)
                val healthStr = when (health.status) {
                    SourceHealthChecker.SourceHealth.Status.GOOD -> "●正常 ${health.latencyMs}ms"
                    SourceHealthChecker.SourceHealth.Status.SLOW -> "●较慢 ${health.latencyMs}ms"
                    SourceHealthChecker.SourceHealth.Status.UNAVAILABLE -> "●不可用"
                    SourceHealthChecker.SourceHealth.Status.UNTESTED -> "○未检测"
                }
                val info = TextView(this).apply {
                    text = (if (i == active) "▶ " else "○ ") + "源 " + (i + 1) + "/" + sources.size +
                        "  ·  " + sourceLabel(url) + "\n  " + healthStr
                    textSize = 14f
                    setPadding(16, 14, 8, 14)
                    isFocusable = true
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    if (i == active) {
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                        setTypeface(typeface, Typeface.BOLD)
                        setBackgroundColor(0x2FFFFFFF.toInt())
                    } else {
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    }
                    // 焦点样式：亮蓝背景 + 白字加粗（和频道列表统一）
                    onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                        if (focused) {
                            setBackgroundColor(0xFF3D8BFF.toInt())
                            setTextColor(0xFFFFFFFF.toInt())
                            setTypeface(typeface, Typeface.BOLD)
                        } else {
                            if (i == active) {
                                setBackgroundColor(0x2FFFFFFF.toInt())
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                                setTypeface(typeface, Typeface.BOLD)
                            } else {
                                setBackgroundColor(0x00000000)
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                                setTypeface(typeface, Typeface.NORMAL)
                            }
                        }
                    }
                }
                info.setOnClickListener {
                    if (i != repository.activeSourceIndex) {
                        repository.activeSourceIndex = i
                        currentChannel = null
                        adapter.setSelected(null)
                        refreshSourceUI()
                        reloadPlaylist(true)
                        Toast.makeText(
                            applicationContext,
                            getString(R.string.switch_source) + "：源 " + (i + 1),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                // 长按删除（遥控器长按 OK 键）
                info.setOnLongClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除直播源")
                        .setMessage("确定删除源 " + (i + 1) + "（" + sourceLabel(url) + "）吗？")
                        .setPositiveButton("删除") { _, _ -> deleteSource(i) }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
                row.addView(info)
                binding.sourceListContainer.addView(row)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun switchToNextSource() {
        val next = repository.switchToNextSource()
        if (next == null) {
            Toast.makeText(applicationContext, "未配置直播源，请在设置中添加", Toast.LENGTH_SHORT).show()
            return
        }
        currentChannel = null
        adapter.setSelected(null)
        updateSourceBar()
        updateSourceStatus()
        reloadPlaylist(true)
        Toast.makeText(
            applicationContext,
            getString(R.string.switch_source) + "：" + sourceLabel(next),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun switchToPrevSource() {
        val prev = repository.switchToPrevSource()
        if (prev == null) {
            Toast.makeText(applicationContext, "未配置直播源，请在设置中添加", Toast.LENGTH_SHORT).show()
            return
        }
        currentChannel = null
        adapter.setSelected(null)
        updateSourceBar()
        updateSourceStatus()
        reloadPlaylist(true)
        Toast.makeText(
            applicationContext,
            getString(R.string.switch_source) + "：" + sourceLabel(prev),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSourceBar() {
        val sources = repository.getSources()
        binding.tvSourceBar.text = if (sources.isEmpty()) {
            getString(R.string.nav_source) + "：未配置（OK 进设置添加）"
        } else {
            "直播源 " + (repository.activeSourceIndex + 1) + "/" + sources.size +
                " · " + sourceLabel(repository.getActiveSource() ?: "") + "  ·  OK 切换"
        }
    }

    private fun sourceLabel(url: String): String {
        return try {
            val u = Uri.parse(url)
            val name = u.path?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
            if (name.isNotBlank() && name.length <= 24) name else (u.host ?: url)
        } catch (e: Exception) {
            url
        }
    }

    // ---------- 扫码局域网管理 ----------

    private fun startRemoteServer() {
        remoteServer = LanRemoteServer(
            onSave = { sources, epg -> handleRemoteSave(sources, epg) },
            onPlayDirect = { url ->
                runOnUiThread {
                    try {
                        playDirect(url, url)
                    } catch (ignored: Throwable) {
                    }
                }
            },
            getStateJson = { stateJson() }
        )
        try {
            remoteServer?.start()
        } catch (e: Exception) {
            remoteServer = null
        }
    }

    private fun handleRemoteSave(sources: List<String>, epg: String?) {
        runOnUiThread {
            try {
                repository.saveSources(sources)
                if (epg != null) {
                    repository.setEpgUrls(
                        epg.split("\n", "\r\n").map { it.trim() }.filter { it.isNotEmpty() }
                    )
                }
                refreshSettingsSourceInput()
                refreshEpgUrlDisplay()
                currentChannel = null
                adapter.setSelected(null)
                updateSourceBar()
                updateSourceStatus()
                updateSourceOptions()
                reloadPlaylist(true)
                loadEpgIfConfigured()
                Toast.makeText(
                    applicationContext,
                    "手机已保存 " + sources.size + " 个直播源，正在刷新…",
                    Toast.LENGTH_LONG
                ).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun stateJson(): String {
        return try {
            JSONObject()
                .put("sources", JSONArray(repository.getSources()))
                .put("active", repository.activeSourceIndex)
                .put("epg", repository.getEpgUrls().joinToString("\n"))
                .put("epgCount", repository.epgProgramCount)
                .toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    /** 弹出添加直播源对话框（替代布局内 EditText，遥控器更易操作） */
    private fun showAddSourceDialog() {
        val et = android.widget.EditText(this).apply {
            hint = "https://example.com/list.m3u"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF999999.toInt())
            setPadding(40, 30, 40, 30)
            setBackgroundColor(0xFF1A1A2E.toInt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("添加直播源")
            .setMessage("输入 M3U/TXT 播放列表 URL（每行一个，支持多个）")
            .setView(et)
            .setPositiveButton("添加") { _, _ ->
                val url = et.text.toString().trim()
                if (url.isNotEmpty()) {
                    addSourceFromText(url)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从文本添加源（支持多行 URL），供弹窗调用 */
    private fun addSourceFromText(text: String) {
        val urls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) {
            Toast.makeText(applicationContext, "请输入有效的 URL", Toast.LENGTH_SHORT).show()
            return
        }
        urls.forEach { repository.addSource(it) }
        refreshSourceUI()
        refreshSettingsSourceInput()
        updateSourceOptions()
        reloadPlaylist(true)
        Toast.makeText(applicationContext, "已添加 ${urls.size} 个直播源", Toast.LENGTH_SHORT).show()
    }

    /** 弹出节目单地址编辑对话框 */
    private fun showEpgUrlDialog() {
        val current = repository.getEpgUrls().joinToString("\n")
        val et = android.widget.EditText(this).apply {
            hint = "https://example.com/epg.xml（可选，每行一个）"
            setText(current)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF999999.toInt())
            setPadding(40, 30, 40, 30)
            setBackgroundColor(0xFF1A1A2E.toInt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("节目单地址（EPG）")
            .setMessage("输入 EPG XML/XML.GZ 地址（可选，每行一个，自动降级）")
            .setView(et)
            .setPositiveButton("保存并加载") { _, _ ->
                val text = et.text.toString().trim()
                val urls = if (text.isEmpty()) emptyList() else text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                repository.setEpgUrls(urls)
                refreshSettingsSourceInput()
                if (urls.isNotEmpty()) {
                    loadEpgIfConfigured()
                    Toast.makeText(applicationContext, "节目单已保存，正在加载...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, "已清除节目单地址", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 节目单分类的控件初始化与监听器 */
    private fun setupEpgSettings() {
        // 显示当前 EPG URL
        refreshEpgUrlDisplay()
        // 启用开关
        updateEpgEnabledUI()
        binding.tvEpgEnabled.setOnClickListener {
            repository.epgEnabled = !repository.epgEnabled
            updateEpgEnabledUI()
            if (repository.epgEnabled) loadEpgIfConfigured()
        }
        // 刷新频率
        updateEpgRefreshUI()
        binding.epgRefresh1h.setOnClickListener { setEpgRefreshHours(1) }
        binding.epgRefresh2h.setOnClickListener { setEpgRefreshHours(2) }
        binding.epgRefreshDaily.setOnClickListener { setEpgRefreshHours(24) }
        // 清除缓存
        binding.btnClearEpgCache.setOnClickListener {
            repository.clearEpgCache()
            Toast.makeText(applicationContext, "节目单缓存已清除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshEpgUrlDisplay() {
        val urls = repository.getEpgUrls()
        binding.tvEpgUrl.text = if (urls.isEmpty()) "未设置（按 OK 输入）" else urls.joinToString("  |  ")
    }

    private fun updateEpgEnabledUI() {
        if (repository.epgEnabled) {
            binding.tvEpgEnabled.text = "开"
            binding.tvEpgEnabled.setTextColor(0xFF4CAF50.toInt())
        } else {
            binding.tvEpgEnabled.text = "关"
            binding.tvEpgEnabled.setTextColor(0xFF9E9E9E.toInt())
        }
    }

    private fun setEpgRefreshHours(hours: Int) {
        repository.epgRefreshHours = hours
        updateEpgRefreshUI()
        Toast.makeText(applicationContext, "节目单刷新频率：${if (hours >= 24) "每天" else "每${hours}小时"}", Toast.LENGTH_SHORT).show()
    }

    private fun updateEpgRefreshUI() {
        val h = repository.epgRefreshHours
        val activeBg = 0xFF3D8BFF.toInt()
        val activeText = 0xFFFFFFFF.toInt()
        val normalBg = 0x00000000.toInt()
        val normalText = 0xFFCCCCCC.toInt()
        listOf(binding.epgRefresh1h to 1, binding.epgRefresh2h to 2, binding.epgRefreshDaily to 24).forEach { (v, hh) ->
            if (h == hh) {
                v.setBackgroundColor(activeBg)
                v.setTextColor(activeText)
            } else {
                v.setBackgroundColor(normalBg)
                v.setTextColor(normalText)
            }
        }
    }

    private fun showScanDialog() {
        val ip = localIpAddress()
        if (ip == null) {
            Toast.makeText(applicationContext, R.string.scan_no_wifi, Toast.LENGTH_LONG).show()
            return
        }
        val server = remoteServer
        if (server == null) {
            Toast.makeText(applicationContext, R.string.scan_server_off, Toast.LENGTH_LONG).show()
            return
        }
        val url = "http://$ip:19090/"
        val qr: Bitmap? = try {
            QrCodeUtil.encode(url, 640)
        } catch (e: Throwable) {
            null
        }
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = android.view.Gravity.CENTER_HORIZONTAL
        content.setPadding(48, 24, 48, 24)
        val qrView = ImageView(this)
        qrView.setPadding(0, 0, 0, 16)
        if (qr != null) {
            qrView.setImageBitmap(qr)
            content.addView(qrView, LinearLayout.LayoutParams(420, 420))
        }
        val tv = android.widget.TextView(this)
        tv.setTextColor(0xFF333333.toInt())
        tv.textSize = 15f
        tv.setLineSpacing(4f, 1f)
        tv.gravity = android.view.Gravity.CENTER_HORIZONTAL
        tv.text = "请用手机（与机顶盒同一 Wi-Fi）扫描：\n$url\n\n可添加/切换直播源、设置节目指南、直接播放"
        content.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_qr_title)
            .setView(content)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun localIpAddress(): String? {
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return null
    }

    // ---------- 设置面板 ----------

    private fun containsFocus(root: View): Boolean {
        if (root.hasFocus()) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (containsFocus(root.getChildAt(i))) return true
            }
        }
        return false
    }

    /** 导航列 ID 集合（单独处理选中样式，不应用通用焦点样式） */
    private val navViewIds = setOf(
        R.id.navLineup, R.id.navEpg, R.id.navRatio, R.id.navDecoder, R.id.navTimeout,
        R.id.navPrefs, R.id.navUpdate, R.id.navDebug, R.id.navAbout, R.id.navExit
    )

    /** 展开式选项 ID 集合（单独管理焦点样式，失焦时恢复选中状态） */
    private val optionViewIds = setOf(
        R.id.timeout5, R.id.timeout10, R.id.timeout15, R.id.timeout20,
        R.id.timeout25, R.id.timeout30, R.id.timeout60,
        R.id.ratiofit, R.id.ratio169, R.id.ratio43, R.id.ratiozoom, R.id.ratiofill,
        R.id.decoderAuto, R.id.decoderHard, R.id.decoderSoft
    )

    /** 给设置面板里所有可聚焦 TextView/Button 统一加焦点样式（亮蓝背景+白字加粗） */
    private fun applySettingsFocusStyles() {
        try {
            fun traverse(view: View) {
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        traverse(view.getChildAt(i))
                    }
                    return
                }
                if (view !is TextView) return
                if (view is android.widget.EditText) return  // 输入框保持原样
                if (view.id in navViewIds) {
                    // 导航项焦点态：使用选中态 drawable + 白字加粗 + 切换 tab
                    val navNormalColor = (view as TextView).currentTextColor
                    view.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                        if (focused) {
                            view.setBackgroundResource(R.drawable.bg_nav_item_selected)
                            view.setTextColor(Color.WHITE)
                            view.setTypeface(view.typeface, Typeface.BOLD)
                            // 焦点时自动切换到对应 tab（修复被覆盖导致不能切换的问题）
                            val navIdx = settingsNavs().indexOf(view)
                            if (navIdx >= 0 && navIdx != currentSettingsTab) {
                                selectSettingsTab(navIdx)
                            }
                        } else {
                            // 失焦时恢复：如果是当前选中 tab 则保持选中态，否则恢复普通态
                            val navIndex = settingsNavs().indexOf(view)
                            if (navIndex == currentSettingsTab) {
                                view.setBackgroundResource(R.drawable.bg_nav_item_selected)
                                view.setTextColor(Color.WHITE)
                                view.setTypeface(view.typeface, Typeface.BOLD)
                            } else {
                                view.setBackgroundResource(0)
                                view.setTextColor(0xFFCCCCCC.toInt())
                                view.setTypeface(view.typeface, Typeface.NORMAL)
                            }
                        }
                    }
                    return
                }
                if (view.id in optionViewIds) return  // 展开式选项单独处理（失焦恢复选中状态）
                if (!view.isFocusable) return
                val normalTextColor = view.currentTextColor
                view.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                    if (focused) {
                        view.setBackgroundResource(R.drawable.bg_item_focused)
                        view.setTextColor(0xFFFFFFFF.toInt())
                        view.setTypeface(view.typeface, Typeface.BOLD)
                    } else {
                        view.setBackgroundResource(0)
                        view.setTextColor(normalTextColor)
                        view.setTypeface(view.typeface, Typeface.NORMAL)
                    }
                }
            }
            traverse(binding.settingsPanel)
        } catch (ignored: Throwable) {
        }
    }

    /** 给展开式选项（超时/比例/解码）设置焦点样式：焦点亮蓝+白字，失焦调用 refreshFn 恢复选中状态 */
    private fun applyOptionFocus(view: TextView, refreshFn: () -> Unit) {
        view.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) {
                view.setBackgroundResource(R.drawable.bg_item_focused)
                view.setTextColor(0xFFFFFFFF.toInt())
                view.paint.isFakeBoldText = true
            } else {
                refreshFn()
            }
        }
    }

    private fun rememberGroupPrefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun rememberLastGroup(group: String) {
        try {
            if (rememberGroupPrefs().getBoolean("remember_group", false)) {
                rememberGroupPrefs().edit().putString("last_group", group).apply()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun settingsNavs(): List<View> = listOf(
        binding.navLineup, binding.navEpg, binding.navRatio, binding.navDecoder,
        binding.navTimeout, binding.navPrefs, binding.navUpdate,
        binding.navDebug, binding.navAbout, binding.navExit
    )

    private fun settingsSections(): List<View> = listOf(
        binding.sectionLineup, binding.sectionEpg, binding.sectionRatio, binding.sectionDecoder,
        binding.sectionTimeout, binding.sectionPrefs, binding.sectionUpdate,
        binding.sectionDebug, binding.sectionAbout, binding.sectionExit
    )

    private fun selectSettingsTab(index: Int, focusDetail: Boolean = false) {
        currentSettingsTab = index
        val navs = settingsNavs()
        val sections = settingsSections()
        sections.forEachIndexed { i, s ->
            s.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navs.forEachIndexed { i, n ->
            if (i == index) {
                n.setBackgroundResource(R.drawable.bg_nav_item_selected)
                (n as android.widget.TextView).setTextColor(Color.WHITE)
                (n as android.widget.TextView).setTypeface((n as android.widget.TextView).typeface, Typeface.BOLD)
            } else {
                (n as android.widget.TextView).setTypeface((n as android.widget.TextView).typeface, Typeface.NORMAL)
                n.setBackgroundResource(0)
                (n as android.widget.TextView).setTextColor(0xFFCCCCCC.toInt())
            }
        }
        // 默认不抢焦点：焦点保持在导航列（上下键可在各分区间切换）；
        // 只有用户按右键（或主动要求进详情）时才定位到当前选中值。
        if (focusDetail) focusValueInSettingsSection(index)
    }

    /** 切 tab 后把焦点定位到当前选中值（展开式分区）。 */
    private fun focusValueInSettingsSection(index: Int) {
        try {
            when (index) {
                1 -> { // 节目单：定位到地址行
                    binding.tvEpgUrl.requestFocus()
                }
                2 -> { // 画面比例
                    val id = when (repository.aspectRatio) {
                        "16:9" -> R.id.ratio169
                        "4:3" -> R.id.ratio43
                        "zoom" -> R.id.ratiozoom
                        "fill" -> R.id.ratiofill
                        else -> R.id.ratiofit
                    }
                    binding.root.findViewById<View>(id)?.requestFocus()
                }
                3 -> { // 播放解码
                    val id = when (playback.currentDecoderMode()) {
                        "hardware" -> R.id.decoderHard
                        "software" -> R.id.decoderSoft
                        else -> R.id.decoderAuto
                    }
                    binding.root.findViewById<View>(id)?.requestFocus()
                }
                4 -> { // 超时换源
                    val id = when (repository.switchTimeoutSec) {
                        5 -> R.id.timeout5
                        10 -> R.id.timeout10
                        15 -> R.id.timeout15
                        20 -> R.id.timeout20
                        25 -> R.id.timeout25
                        30 -> R.id.timeout30
                        else -> R.id.timeout60
                    }
                    binding.root.findViewById<View>(id)?.requestFocus()
                }
                7 -> { // 调试：定位到清空日志
                    binding.btnClearCrashLog.requestFocus()
                }
                else -> {
                    val section = settingsSections()[index.coerceIn(0, settingsSections().size - 1)]
                    val target = firstFocusableChild(section)
                    if (target != null) {
                        target.requestFocus()
                    } else {
                        // 详情区无可聚焦项：回退到导航列，避免焦点丢失
                        val navs = settingsNavs()
                        navs[index.coerceIn(0, navs.size - 1)].requestFocus()
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 深度优先找第一个可聚焦子 View。 */
    private fun firstFocusableChild(parent: View): View? {
        if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.isFocusable) return child
                val sub = firstFocusableChild(child)
                if (sub != null) return sub
            }
        }
        return null
    }

    private fun setupSettingsPanel() {
        setupSettingsTabs()
        refreshSettingsSourceInput()

        binding.btnImportFile.setOnClickListener {
            try {
                openDocument.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Toast.makeText(
                    applicationContext,
                    "无法打开文件选择器（系统无文件管理），请改用播放列表 URL 加载",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        binding.btnRefreshSource.setOnClickListener {
            val url = repository.getActiveSource()
            if (url.isNullOrEmpty()) {
                Toast.makeText(applicationContext, "未配置直播源", Toast.LENGTH_SHORT).show()
            } else {
                currentChannel = null
                adapter.setSelected(null)
                reloadPlaylist(true)
                Toast.makeText(applicationContext, "正在刷新当前源...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddSource.setOnClickListener { showAddSourceDialog() }

        // 一键测速
        binding.btnCheckAllSources.setOnClickListener {
            val sources = repository.getSources()
            if (sources.isEmpty()) {
                Toast.makeText(applicationContext, "暂无直播源", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnCheckAllSources.text = "检测中..."
            binding.btnCheckAllSources.isEnabled = false
            sourceHealthChecker.checkAllSources(
                urls = sources,
                timeoutMs = 5000,
                onProgress = { done, total, health ->
                    repository.setSourceHealth(health.url, health)
                    runOnUiThread { updateSourceOptions() }
                },
                onComplete = { results ->
                    runOnUiThread {
                        binding.btnCheckAllSources.text = "一键测速"
                        binding.btnCheckAllSources.isEnabled = true
                        updateSourceOptions()
                        if (repository.autoSelectFastest) {
                            val fastestIdx = sourceHealthChecker.findFastestAvailable(results)
                            if (fastestIdx >= 0 && fastestIdx != repository.activeSourceIndex) {
                                repository.activeSourceIndex = fastestIdx
                                currentChannel = null
                                adapter.setSelected(null)
                                refreshSourceUI()
                                reloadPlaylist(true)
                                Toast.makeText(applicationContext, "已自动切换到最快源（源${fastestIdx + 1}）", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(applicationContext, "测速完成，当前已是最快源", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val available = results.count { it.available }
                            Toast.makeText(applicationContext, "测速完成：${available}/${results.size} 个源可用", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }

        // 自动优选开关
        updateAutoSelectFastestUI()
        binding.tvAutoSelectFastest.setOnClickListener {
            repository.autoSelectFastest = !repository.autoSelectFastest
            updateAutoSelectFastestUI()
            Toast.makeText(applicationContext, if (repository.autoSelectFastest) "已开启自动优选" else "已关闭自动优选", Toast.LENGTH_SHORT).show()
        }
        binding.tvCurrentSource.setOnClickListener {
            switchToNextSource()
            refreshSourceUI()
            refreshSettingsSourceInput()
        }
        binding.tvEpgUrl.setOnClickListener { showEpgUrlDialog() }
        // 直接播放流地址已移到诊断分类（btnPlayDirect 已从布局移除）
        binding.btnCloseSettings.setOnClickListener { hideSettingsPanel() }
        binding.btnScanManage.setOnClickListener { showScanDialog() }

        // ===== 节目单（EPG）分类 =====
        setupEpgSettings()

        binding.btnClearFavorites.setOnClickListener {
            favorites.clear()
            repository.setFavorites(favorites)
            adapter.favorites = favorites
            updateFavCount()
            Toast.makeText(applicationContext, R.string.cleared, Toast.LENGTH_SHORT).show()
        }

        // 直播源切换列表（多播放列表）
        updateSourceOptions()

        // 超时换源（展开式选项）
        setupTimeoutOptions()
        updateTimeoutSelection()

        // 偏好设置
        binding.chkShowClock.isChecked = repository.showClock
        binding.chkShowClock.setOnCheckedChangeListener { _, checked ->
            repository.showClock = checked
            if (checked) updateClockVisibility()
        }
        binding.chkShowSpeed.isChecked = repository.showSpeed
        binding.chkShowSpeed.setOnCheckedChangeListener { _, checked ->
            repository.showSpeed = checked
        }
        binding.chkReverseZap.isChecked = repository.reverseZap
        binding.chkReverseZap.setOnCheckedChangeListener { _, checked ->
            repository.reverseZap = checked
        }
        binding.chkCrossCategory.isChecked = repository.crossCategory
        binding.chkCrossCategory.setOnCheckedChangeListener { _, checked ->
            repository.crossCategory = checked
        }

        binding.chkAutoResume.isChecked = repository.autoResume
        binding.chkAutoResume.setOnCheckedChangeListener { _, checked ->
            repository.autoResume = checked
        }

        binding.chkAutoHide.isChecked = repository.autoHideOverlay
        binding.chkAutoHide.setOnCheckedChangeListener { _, checked ->
            repository.autoHideOverlay = checked
            if (checked) showOverlay()
        }

        setupAspectRatioOptions()
        updateAspectRatioSelection()

        setupDecoderOptions()
        updateDecoderSelection()

        // 记住上次分组
        binding.chkRememberGroup.isChecked = rememberGroupPrefs().getBoolean("remember_group", false)
        binding.chkRememberGroup.setOnCheckedChangeListener { _, checked ->
            rememberGroupPrefs().edit().putBoolean("remember_group", checked).apply()
        }

        updateFavCount()

        binding.tvVersion.text = "v" + BuildConfig.VERSION_NAME
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }
        // 启动自动检查更新开关
        binding.switchAutoUpdate.isChecked = autoUpdateCheck
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            autoUpdateCheck = isChecked
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("auto_update_check", isChecked).apply()
        }

        refreshCrashLog()
        binding.btnExportCrashLog.setOnClickListener { launchCrashExport() }
        binding.btnTestCrash.setOnClickListener {
            // 测试 Bugly 崩溃上报：故意抛出异常，验证崩溃能否自动上报到 Bugly 后台
            throw RuntimeException("Bugly测试崩溃 - 验证崩溃自动上报功能")
        }
        binding.btnExportAboutCrash.setOnClickListener { launchCrashExport() }
        binding.btnClearCrashLog.setOnClickListener {
            crashFile().delete()
            rememberGroupPrefs().edit().putBoolean("crash_prompted", false).apply()
            binding.tvCrashLog.text = "无"
            Toast.makeText(applicationContext, R.string.cleared_crash_log, Toast.LENGTH_SHORT).show()
        }

        // 退出
        binding.btnExitApp.setOnClickListener { confirmExit() }

        binding.tvAbout.text = getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME +
            "\n播放内核：Media3 ExoPlayer（开源）" +
            "\n代码仓库：https://github.com/cyj265/lanxing-tv" +
            "\n开源许可：Apache-2.0 / MIT / GPL-2.0" +
            "\n部分源码来自开源项目（详见仓库 README 来源致谢）"

        // 统一设置面板所有可聚焦元素的焦点样式（亮蓝背景+白字，和频道列表一致）
        applySettingsFocusStyles()
    }

    private fun setupSettingsTabs() {
        val navs = settingsNavs()
        navs.forEachIndexed { i, nav ->
            nav.setOnFocusChangeListener { _, focused ->
                if (focused) selectSettingsTab(i)
            }
        }
        // 退出导航：点击直接弹确认
        binding.navExit.setOnClickListener { confirmExit() }
        selectSettingsTab(0)
    }

        private fun refreshSettingsSourceInput() {
        try {
            refreshEpgUrlDisplay()
        } catch (ignored: Throwable) {
        }
    }

    /** 刷新直播源相关 UI（当前源标签 + 源列表 + 状态） */
    private fun refreshSourceUI() {
        try {
            updateCurrentSourceLabel()
            updateSourceOptions()
            updateSourceStatus()
            updateSourceBar()
        } catch (ignored: Throwable) {
        }
    }

    /** 更新当前源标签 */
    private fun updateCurrentSourceLabel() {
        try {
            val sources = repository.getSources()
            if (sources.isEmpty()) {
                binding.tvCurrentSource.text = "未配置直播源"
                return
            }
            val idx = repository.activeSourceIndex
            val url = sources.getOrNull(idx) ?: ""
            binding.tvCurrentSource.text = "源 " + (idx + 1) + "/" + sources.size + "  ·  " + sourceLabel(url)
        } catch (ignored: Throwable) {
        }
    }


    /** 删除指定源 */
    private fun deleteSource(index: Int) {
        val sources = repository.getSources().toMutableList()
        if (index < 0 || index >= sources.size) return
        val removed = sources.removeAt(index)
        if (sources.isEmpty()) {
            repository.saveSources(emptyList())
            repository.activeSourceIndex = 0
        } else {
            repository.saveSources(sources)
            if (repository.activeSourceIndex >= sources.size) {
                repository.activeSourceIndex = sources.size - 1
            }
        }
        currentChannel = null
        adapter.setSelected(null)
        refreshSourceUI()
        if (sources.isNotEmpty()) reloadPlaylist(true)
        Toast.makeText(applicationContext, "已删除直播源", Toast.LENGTH_SHORT).show()
    }


    private fun updateAutoSelectFastestUI() {
        try {
            if (repository.autoSelectFastest) {
                binding.tvAutoSelectFastest.text = "开"
                binding.tvAutoSelectFastest.setTextColor(0xFF4CAF50.toInt())
            } else {
                binding.tvAutoSelectFastest.text = "关"
                binding.tvAutoSelectFastest.setTextColor(0xFF9E9E9E.toInt())
            }
        } catch (ignored: Throwable) {}
    }

    private fun updateSourceStatus() {
        try {
            val sources = repository.getSources()
            val active = repository.getActiveSource()
            val sb = StringBuilder()
            sb.append("已配置 ").append(sources.size).append(" 个直播源")
            if (active != null) {
                sb.append("\n当前源：").append(sourceLabel(active))
                val updatedAt = repository.getSourceUpdatedAt(active)
                sb.append("\n").append(getString(R.string.source_updated)).append("：")
                sb.append(
                    if (updatedAt > 0)
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))
                    else getString(R.string.source_never)
                )
            } else {
                sb.append("\n当前源：未配置")
            }
            val epgList = repository.getEpgUrls()
            sb.append("\n节目指南：").append(
                if (epgList.isEmpty()) "未设置" else "已配置 " + epgList.size + " 个地址"
            )
            val count = repository.epgProgramCount
            if (count > 0) sb.append("（已加载 ").append(count).append(" 条节目）")
            // v1.14.5：移除启动时的直播源状态 Toast，避免影响观感
            // 状态信息已在 tvStatus 中显示，用户可在设置中查看详细直播源信息
        } catch (ignored: Throwable) {
        }
    }

    private fun updateFavCount() {
        binding.tvFavCount.text = getString(R.string.fav_count, favorites.size)
    }

    private fun refreshCrashLog() {
        val f = crashFile()
        binding.tvCrashLog.text = if (f.exists()) {
            try {
                f.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                "读取失败"
            }
        } else {
            "无"
        }
    }

    private fun launchCrashExport() {
        if (!crashFile().exists()) {
            Toast.makeText(applicationContext, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val name = "app-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date()) + ".txt"
        try {
            createLogDoc.launch(name)
        } catch (e: ActivityNotFoundException) {
            // TV 设备无文件管理器，降级到直接写入应用外部目录
            exportCrashLogToExternal(name)
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "无法打开保存窗口：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 降级方案：直接写入应用外部文件目录（无需权限，文件管理器可访问） */
    private fun exportCrashLogToExternal(name: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val outFile = java.io.File(dir, name)
            crashFile().copyTo(outFile, overwrite = true)
            Toast.makeText(applicationContext, "已导出到：${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportCrashLogTo(uri: Uri) {
        try {
            val f = crashFile()
            if (!f.exists()) return
            val os = contentResolver.openOutputStream(uri)
            if (os == null) {
                Toast.makeText(applicationContext, "无法写入目标位置，请重试或选择其他位置", Toast.LENGTH_LONG).show()
                return
            }
            os.use { out ->
                out.write(f.readBytes())
            }
            Toast.makeText(applicationContext, R.string.exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 超时换源 ----------

    private val timeoutCycle = listOf(5, 10, 15, 20, 25, 30, 60)

    private fun cycleTimeout() {
        val current = repository.switchTimeoutSec
        val idx = timeoutCycle.indexOf(current).let { if (it < 0) 0 else it }
        val next = timeoutCycle[(idx + 1) % timeoutCycle.size]
        repository.switchTimeoutSec = next
        playback.setSourceTimeoutMs(next * 1000L)
        updateTimeoutSelection()
        Toast.makeText(applicationContext, getString(R.string.switch_timeout) + "：" + next + " 秒", Toast.LENGTH_SHORT).show()
    }

    private fun updateTimeoutSelection() {
        val current = repository.switchTimeoutSec
        val timeoutViews = listOf(
            5 to binding.timeout5, 10 to binding.timeout10, 15 to binding.timeout15,
            20 to binding.timeout20, 25 to binding.timeout25, 30 to binding.timeout30,
            60 to binding.timeout60
        )
        for ((sec, view) in timeoutViews) {
            if (sec == current) {
                view.setBackgroundColor(0x4FFFFFFF.toInt())
                view.setTextColor(0xFF64B5F6.toInt())
                view.paint.isFakeBoldText = true
            } else {
                view.setBackgroundColor(0x00000000)
                view.setTextColor(0xFFCCCCCC.toInt())
                view.paint.isFakeBoldText = false
            }
        }
    }

    private fun setupTimeoutOptions() {
        val timeoutViews = listOf(
            5 to binding.timeout5, 10 to binding.timeout10, 15 to binding.timeout15,
            20 to binding.timeout20, 25 to binding.timeout25, 30 to binding.timeout30,
            60 to binding.timeout60
        )
        for ((sec, view) in timeoutViews) {
            applyOptionFocus(view) { updateTimeoutSelection() }
            view.setOnClickListener {
                repository.switchTimeoutSec = sec
                playback.setSourceTimeoutMs(sec * 1000L)
                updateTimeoutSelection()
                Toast.makeText(applicationContext, "换源超时：$sec 秒", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 检查更新（自动下载安装） ----------

    /** 启动时静默检查更新：有更新弹窗确认，没更新不打扰，失败也不打扰 */
    /** 从 APK 文件名提取构建号，如 iptv-player-v244.apk -> 244 */
    private fun extractBuildNumber(apkFileName: String): Int {
        return try {
            val m = Regex("v(\\d+)\\.apk$").find(apkFileName)
            m?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun checkUpdateSilent() {
        Thread {
            try {
                val conn = URL(
                    "https://api.github.com/repos/cyj265/lanxing-tv/releases/latest"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "LanXingTV")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                var apkName: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url")
                        apkName = a.getString("name")
                        break
                    }
                }
                // 用构建号检测更新：即使版本号相同，构建号增加也提示
                val latestBuild = if (apkName != null) extractBuildNumber(apkName) else 0
                val currentBuild = BuildConfig.BUILD_NUMBER
                val newer = latestBuild > currentBuild
                if (newer && !apkUrl.isNullOrEmpty()) {
                    runOnUiThread {
                        try {
                            AlertDialog.Builder(this)
                                .setTitle("发现新版本")
                                .setMessage("揽星TV v$tag (构建 #$latestBuild) 已发布\n当前版本：v${BuildConfig.VERSION_NAME} (构建 #$currentBuild)\n\n是否立即更新？")
                                .setPositiveButton("立即更新") { _, _ ->
                                    downloadAndInstall(apkUrl)
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                // 没更新或没有APK：什么都不做（不打扰）
            } catch (e: Exception) {
                // 检查失败：什么都不做（不打扰）
            }
        }.start()
    }

    private fun checkUpdate() {
        binding.tvUpdateStatus.text = getString(R.string.checking_update)
        Thread {
            try {
                val conn = URL(
                    "https://api.github.com/repos/cyj265/lanxing-tv/releases/latest"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "LanXingTV")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                var apkName: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url")
                        apkName = a.getString("name")
                        break
                    }
                }
                // 用构建号检测更新
                val latestBuild = if (apkName != null) extractBuildNumber(apkName) else 0
                val currentBuild = BuildConfig.BUILD_NUMBER
                val newer = latestBuild > currentBuild
                runOnUiThread {
                    try {
                        if (!newer || apkUrl.isNullOrEmpty()) {
                            binding.tvUpdateStatus.text =
                                getString(R.string.update_latest) + "（v" + BuildConfig.VERSION_NAME + " #$currentBuild）"
                        } else {
                            binding.tvUpdateStatus.text =
                                getString(R.string.update_found) + " v" + tag + " #$latestBuild"
                            downloadAndInstall(apkUrl)
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    try {
                        binding.tvUpdateStatus.text =
                            getString(R.string.update_failed, e.message ?: "网络错误")
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }.start()
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    private fun downloadAndInstall(originalUrl: String) {
        // 双线下载：先直连，失败自动换加速中转
        val proxyUrl = if (originalUrl.startsWith("https://github.com/")) {
            "https://gh-proxy.com/" + originalUrl
        } else {
            originalUrl
        }
        val urls = if (originalUrl == proxyUrl) {
            listOf(originalUrl)  // 非 GitHub 链接只有一条
        } else {
            listOf(originalUrl, proxyUrl)  // GitHub 链接：直连 + 加速
        }
        val urlNames = listOf("直连", "加速")

        Thread {
            var lastError: String? = null
            for ((idx, downloadUrl) in urls.withIndex()) {
                val urlName = if (urls.size > 1) urlNames.getOrElse(idx) { "线路${idx+1}" } else ""
                try {
                    runOnUiThread {
                        try {
                            binding.tvUpdateStatus.text = if (urlName.isNotEmpty()) "正在$urlName 下载..." else "正在下载..."
                        } catch (ignored: Throwable) {}
                    }
                    val dir = File(cacheDir, "apk")
                    dir.mkdirs()
                    val apk = File(dir, "update.apk")
                    val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "LanXingTV")
                    val code = conn.responseCode
                    if (code !in 200..399) {
                        throw Exception("HTTP $code")
                    }
                    val total = conn.contentLengthLong
                    val input = conn.inputStream
                    val output = apk.outputStream()
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPercent = -1
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPercent && pct % 5 == 0) {
                                lastPercent = pct
                                val p = pct
                                val name = urlName
                                runOnUiThread {
                                    try {
                                        binding.tvUpdateStatus.text = if (name.isNotEmpty()) "$name 下载中 $p%" else "下载中 $p%"
                                    } catch (ignored: Throwable) {}
                                }
                            }
                        }
                    }
                    output.flush()
                    output.close()
                    input.close()
                    // 下载成功，安装
                    runOnUiThread {
                        try {
                            binding.tvUpdateStatus.text = getString(R.string.update_done)
                            installApk(apk)
                        } catch (ignored: Throwable) {}
                    }
                    return@Thread  // 下载成功，退出
                } catch (e: Exception) {
                    lastError = e.message ?: "下载失败"
                    // 如果还有下一个链接，自动切换
                    if (idx < urls.size - 1) {
                        runOnUiThread {
                            try {
                                binding.tvUpdateStatus.text = "${urlName}失败（${lastError}），切换${urlNames[idx+1]}..."
                            } catch (ignored: Throwable) {}
                        }
                        Thread.sleep(500)
                    }
                }
            }
            // 所有链接都失败
            runOnUiThread {
                try {
                    binding.tvUpdateStatus.text = getString(R.string.update_failed, lastError ?: "所有线路均下载失败")
                } catch (ignored: Throwable) {}
            }
        }.start()
    }

    private fun installApk(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "无法打开安装器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 画面比例 ----------

    private val ratioCycle = listOf("fit", "16:9", "4:3", "zoom", "fill")

    // ---------- 解码方式 ----------

    private val decoderModeCycle = listOf("auto", "hardware", "software")

    private fun labelForMode(mode: String): String = when (mode) {
        "hardware" -> getString(R.string.decoder_hardware)
        "software" -> getString(R.string.decoder_software)
        else -> getString(R.string.decoder_auto)
    }

    private fun updateDecoderSelection() {
        val current = playback.currentDecoderMode()
        val decoderViews = listOf(
            "auto" to binding.decoderAuto,
            "hardware" to binding.decoderHard,
            "software" to binding.decoderSoft
        )
        for ((key, view) in decoderViews) {
            if (key == current) {
                view.setBackgroundColor(0x4FFFFFFF.toInt())
                view.setTextColor(0xFF64B5F6.toInt())
                view.paint.isFakeBoldText = true
            } else {
                view.setBackgroundColor(0x00000000)
                view.setTextColor(0xFFCCCCCC.toInt())
                view.paint.isFakeBoldText = false
            }
        }
    }

    private fun setupDecoderOptions() {
        val decoderViews = listOf(
            "auto" to binding.decoderAuto,
            "hardware" to binding.decoderHard,
            "software" to binding.decoderSoft
        )
        for ((key, view) in decoderViews) {
            applyOptionFocus(view) { updateDecoderSelection() }
            view.setOnClickListener {
                playback.applyDecoderMode(key)
                updateDecoderSelection()
                Toast.makeText(applicationContext, getString(R.string.decoder_mode) + "：" + labelForMode(key), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cycleAspectRatio() {
        val current = repository.aspectRatio
        val idx = ratioCycle.indexOf(current)
        val next = ratioCycle[(idx + 1 + ratioCycle.size) % ratioCycle.size]
        repository.aspectRatio = next
        playback.setAspectRatio(next)
        updateAspectRatioSelection()
    }

    private fun updateAspectRatioSelection() {
        val current = repository.aspectRatio
        val ratioViews = listOf(
            "fit" to binding.ratiofit, "16:9" to binding.ratio169,
            "4:3" to binding.ratio43, "zoom" to binding.ratiozoom,
            "fill" to binding.ratiofill
        )
        for ((key, view) in ratioViews) {
            if (key == current) {
                view.setBackgroundColor(0x4FFFFFFF.toInt())
                view.setTextColor(0xFF64B5F6.toInt())
                view.paint.isFakeBoldText = true
            } else {
                view.setBackgroundColor(0x00000000)
                view.setTextColor(0xFFCCCCCC.toInt())
                view.paint.isFakeBoldText = false
            }
        }
    }

    private fun setupAspectRatioOptions() {
        val ratioViews = listOf(
            "fit" to binding.ratiofit, "16:9" to binding.ratio169,
            "4:3" to binding.ratio43, "zoom" to binding.ratiozoom,
            "fill" to binding.ratiofill
        )
        for ((key, view) in ratioViews) {
            applyOptionFocus(view) { updateAspectRatioSelection() }
            view.setOnClickListener {
                repository.aspectRatio = key
                playback.setAspectRatio(key)
                updateAspectRatioSelection()
                val label = when (key) {
                    "16:9" -> "16:9"
                    "4:3" -> "4:3"
                    "zoom" -> "缩放"
                    "fill" -> "填充"
                    else -> "适应"
                }
                Toast.makeText(applicationContext, "画面比例：$label", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 播放列表加载 ----------


    private fun reloadPlaylist(forceRefresh: Boolean = false) {
        val url = repository.getActiveSource()
        if (url.isNullOrEmpty()) {
            val cached = repository.loadLocalChannels()
            if (!cached.isNullOrEmpty()) onChannelsLoaded(cached)
            return
        }
        // ★ v1.13.1：直播源缓存 24 小时内直接使用（频道列表相对稳定，无需每次启动重新下载）。
        // 用户手动操作（切换源/保存/扫码/刷新）时 forceRefresh=true 强制下载最新列表。
        if (!forceRefresh) {
            val updatedAt = repository.getSourceUpdatedAt(url)
            val cached = repository.loadCachedChannels(url)
            if (updatedAt > 0 &&
                System.currentTimeMillis() - updatedAt < 24 * 3600_000L &&
                !cached.isNullOrEmpty()
            ) {
                onChannelsLoaded(cached)
                return
            }
        }
        Thread {
            try {
                val content = HttpLoader.fetch(url)
                val channels = PlaylistParser.parseAuto(content)
                if (channels.isNotEmpty()) {
                    repository.saveChannels(channels, url)
                }
                runOnUiThread {
                    try {
                        onChannelsLoaded(channels)
                    } catch (e: Throwable) {
                        binding.tvStatus.text =
                            getString(R.string.load_failed) + "：" + (e.message ?: "列表渲染失败")
                    }
                }
            } catch (e: Throwable) {
                val cached = repository.loadCachedChannels(url)
                val detail = e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    try {
                        if (!cached.isNullOrEmpty()) {
                            onChannelsLoaded(cached)
                            binding.tvStatus.text = getString(R.string.load_failed) + "（已用缓存）"
                        } else {
                            binding.tvStatus.text = getString(R.string.load_failed) + "：" + detail
                            Toast.makeText(applicationContext, R.string.load_failed, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e2: Throwable) {
                        binding.tvStatus.text =
                            getString(R.string.load_failed) + "：" + (e2.message ?: "未知错误")
                    }
                }
            }
        }.start()
    }

    private fun onChannelsLoaded(channels: List<Channel>) {
        allChannels = channels
        binding.tvStatus.text = getString(R.string.channel_list) + " · " + channels.size + " 个频道"
        // 更新左侧分组列表
        val groupSet = LinkedHashSet<String?>()
        groupSet.add(null)  // 全部
        channels.forEach { groupSet.add(it.group) }
        groupAdapter.submitGroups(groupSet.toList())
        groupAdapter.setSelected(currentGroup)
        applyFilter()
        if (channels.isEmpty()) {
            binding.tvChannelName.text = getString(R.string.no_channels)
        }
        updateSourceBar()
        updateSourceStatus()
        loadEpgIfConfigured()
        // 启动播放统一由 waitForSurfaceAndResume() 处理（等 surfaceCreated 后再播）。
        // 仅当 surface 已就绪（说明 waitForSurfaceAndResume 已执行过但当时无频道可播）
        // 且当前还没播放时，才在这里补调一次，避免重复初始化播放器。
        if (currentChannel == null && channels.isNotEmpty() && repository.autoResume && surfaceReady) {
            logStartup("onChannelsLoaded: surface already ready but no channel playing, resumeLastChannel")
            resumeLastChannel()
        }
    }

    // ---------- 本地文件导入 ----------

    private fun importLocalFile(uri: Uri) {
        Thread {
            try {
                val content = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (content == null) return@Thread
                var text = String(content, Charsets.UTF_8)
                if (text.contains('\uFFFD')) {
                    text = String(content, java.nio.charset.Charset.forName("GBK"))
                }
                val channels = PlaylistParser.parseAuto(text)
                if (channels.isEmpty()) {
                    runOnUiThread { Toast.makeText(applicationContext, R.string.no_channels, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                repository.saveLocalChannels(channels)
                runOnUiThread {
                    try {
                        onChannelsLoaded(channels)
                        hideSettingsPanel()
                        Toast.makeText(applicationContext, R.string.importing, Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        Toast.makeText(applicationContext, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(applicationContext, R.string.load_failed, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }


    // ---------- 频道筛选 ----------

    private var lastFocusedChannel: com.cyj265.iptvplayer.data.Channel? = null
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var previewRunnable: Runnable? = null

    /** 已废弃：频道焦点移动不再自动换台（OK 键才播放），避免频繁播放导致卡顿 */
    private fun previewChannel(ch: com.cyj265.iptvplayer.data.Channel) {
        // no-op: 焦点移动只更新节目信息，OK 才播放
    }

    private fun applyFilter() {
        try {
            val query = binding.searchInput.text.toString().trim().lowercase(Locale.getDefault())
            val filtered = allChannels.filter { ch ->
                val matchQuery = query.isEmpty() || ch.name.lowercase(Locale.getDefault()).contains(query)
                val matchFav = !showFavoritesOnly || favorites.contains(ch.url)
                val matchGroup = currentGroup == null || ch.group == currentGroup
                matchQuery && matchFav && matchGroup
            }
            adapter.submitChannels(filtered)
            adapter.setSelected(currentChannel?.id)
        } catch (ignored: Throwable) {
        }
    }

    // ---------- 播放 ----------

    private fun onChannelClick(channel: Channel) {
        currentChannel = channel
        repository.lastChannelId = channel.id
        adapter.setSelected(channel.id)
        updateProgramInfo()
        playback.play(channel.sources.ifEmpty { listOf(channel.url) }, channel.name)
        updateNowPlaying()
        updateLineupLabel()
        hideChannelPanel()
    }

    private var surfaceReady = false
    private var surfaceResumePending = false
    private val surfaceResumeHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 等待 SurfaceView 的 Surface 创建完成后再恢复播放，避免渲染到未就绪 Surface 导致黑屏闪退 */
    /** 遍历 PlayerView 子视图查找 SurfaceView（兼容各版本 Media3 API） */
    private fun findSurfaceView(view: android.view.View): android.view.SurfaceView? {
        if (view is android.view.SurfaceView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findSurfaceView(child)
                if (result != null) return result
            }
        }
        return null
    }

    private fun waitForSurfaceAndResume() {
        try {
            val surfaceView = findSurfaceView(binding.playerView)
            if (surfaceView != null) {
                val holder = surfaceView.holder
                if (holder.surface != null && holder.surface.isValid) {
                    // Surface 已就绪，直接播放
                    surfaceReady = true
                    logStartup("Surface already ready, resume immediately")
                    resumeLastChannel()
                    return
                }
                // 等待 surfaceCreated 回调
                surfaceResumePending = true
                logStartup("Surface not ready, waiting for surfaceCreated...")
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(h: android.view.SurfaceHolder) {
                        surfaceReady = true
                        logStartup("surfaceCreated callback received")
                        if (surfaceResumePending) {
                            surfaceResumePending = false
                            resumeLastChannel()
                        }
                        h.removeCallback(this)
                    }
                    override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                        surfaceReady = false
                        logStartup("surfaceDestroyed callback received")
                    }
                })
                // 超时保护：5秒内 Surface 未就绪则强制播放
                surfaceResumeHandler.postDelayed({
                    if (surfaceResumePending) {
                        surfaceResumePending = false
                        logStartup("Surface wait timeout (5s), force resume")
                        resumeLastChannel()
                    }
                }, 5000)
            } else {
                logStartup("SurfaceView not found, fallback to delayed resume")
                surfaceResumeHandler.postDelayed({ resumeLastChannel() }, 500)
            }
        } catch (e: Exception) {
            logStartup("waitForSurfaceAndResume exception: ${e.message}")
            window.decorView.postDelayed({ resumeLastChannel() }, 500)
        }
    }

    /** 启动日志：写入 app.log 便于定位启动/播放问题 */
    private fun logStartup(msg: String) {
        try {
            val file = crashFile()
            // 日志大小限制：超过 500KB 自动清空，避免长期使用占用过多存储空间
            if (file.exists() && file.length() > 500 * 1024) {
                file.delete()
            }
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            val existing = if (file.exists()) file.readText(Charsets.UTF_8) else ""
            file.writeText("$existing\n[$timestamp][Startup] $msg", Charsets.UTF_8)
        } catch (ignored: Exception) {}
    }

    private fun resumeLastChannel() {
        val lastId = repository.lastChannelId
        if (lastId == null) {
            logStartup("resumeLastChannel: lastChannelId is null, skip")
            return
        }
        val ch = allChannels.firstOrNull { it.id == lastId }
        if (ch == null) {
            logStartup("resumeLastChannel: channel not found for id=$lastId, channels=${allChannels.size}, skip")
            return
        }
        logStartup("resumeLastChannel: playing channel=${ch.name} sources=${ch.sources.size}")
        currentChannel = ch
        adapter.setSelected(ch.id)
        playback.play(ch.sources.ifEmpty { listOf(ch.url) }, ch.name)
        updateNowPlaying()
        updateLineupLabel()
        logStartup("resumeLastChannel: playback.play() called")
    }

    private fun playDirect(url: String, name: String) {
        val channel = Channel(
            id = "direct-${url.hashCode()}",
            name = name,
            url = url,
            group = "直接播放",
            logo = "",
            tvgId = name
        )
        currentChannel = channel
        repository.lastChannelId = channel.id
        adapter.setSelected(null)
        playback.play(listOf(url), name)
        updateNowPlaying()
        updateLineupLabel()
    }

    private fun switchChannel(delta: Int) {
        if (allChannels.isEmpty()) return
        val current = currentChannel
        // 换台反转：上下键逻辑反转
        val realDelta = if (repository.reverseZap) -delta else delta
        if (showFavoritesOnly) {
            val favChannels = allChannels.filter { favorites.contains(it.url) }
            if (favChannels.isEmpty()) return
            val favIdx = favChannels.indexOfFirst { it.id == current?.id }
            val next = if (favIdx < 0) 0 else (favIdx + realDelta + favChannels.size) % favChannels.size
            onChannelClick(favChannels[next])
            return
        }
        // 跨选分类：关闭时只在当前分组内循环
        if (!repository.crossCategory && current != null) {
            val sameGroup = allChannels.filter { it.group == current.group }
            if (sameGroup.size > 1) {
                val gIdx = sameGroup.indexOfFirst { it.id == current.id }
                val next = if (gIdx < 0) 0 else (gIdx + realDelta + sameGroup.size) % sameGroup.size
                onChannelClick(sameGroup[next])
                return
            }
        }
        val idx = allChannels.indexOfFirst { it.id == current?.id }
        val next = if (idx < 0) 0 else (idx + realDelta + allChannels.size) % allChannels.size
        onChannelClick(allChannels[next])
    }

    private fun toggleFavoriteCurrent() {
        val ch = currentChannel ?: return
        if (favorites.contains(ch.url)) {
            favorites.remove(ch.url)
        } else {
            favorites.add(ch.url)
        }
        repository.setFavorites(favorites)
        adapter.favorites = favorites
        updateFavoriteIcon()
        updateFavCount()
    }

    private fun updateFavoriteIcon() {
        val ch = currentChannel ?: return
        binding.btnFavoriteCurrent.alpha = if (favorites.contains(ch.url)) 1f else 0.4f
    }

    // ---------- EPG（支持多个地址，全部加载合并） ----------

    private fun loadEpgIfConfigured() {
        // v1.14.0：节目单开关（设置里可关闭）
        if (!repository.epgEnabled) {
            epgLoadState = EpgLoadState.NOT_CONFIGURED
            epgPrograms = emptyMap()
            updateNowPlaying()
            return
        }
        val epgUrls = repository.getEpgUrls()
        if (epgUrls.isEmpty()) {
            epgLoadState = EpgLoadState.NOT_CONFIGURED
            updateNowPlaying()
            return
        }
        // 节流：距上次尝试不足 10 秒不重复加载
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastEpgAttemptAt < 10000 && epgLoadState == EpgLoadState.LOADING) return
        lastEpgAttemptAt = nowMs
        // ★ v1.13.1：EPG 本地缓存 2 小时内直接使用（节目单按时间线显示，无需每次启动重新下载）。
        // 缓存过期或不存在才后台重新获取；加载完成后再写回缓存。
        val cachedAge = nowMs - repository.epgUpdatedAt
        val epgCache = repository.loadEpgCache()
        val cacheThresholdMs = repository.epgRefreshHours * 3600_000L
        if (cachedAge in 0 until cacheThresholdMs && epgCache != null) {
            val channelsSnapshot = allChannels
            epgPrograms = indexEpgWithChannels(epgCache, channelsSnapshot)
            epgLoadState = EpgLoadState.READY
            updateNowPlaying()
            adapter.epgNow = buildEpgNowMap()
            adapter.epgNext = buildEpgNextMap()
            updateProgramInfo()
            return
        }
        epgLoadState = EpgLoadState.LOADING
        updateProgramInfo()
        Thread {
            var totalPrograms = 0
            val mergedPrograms = ArrayList<EpgProgram>()
            val mergedNames = HashMap<String, String>()
            var loadError: String? = null
            try {
                for (epgUrl in epgUrls) {
                    try {
                        val content = HttpLoader.fetch(epgUrl)
                        val data = EpgParser.parse(content)
                        mergedPrograms.addAll(data.programs)
                        mergedNames.putAll(data.channelNames)
                        totalPrograms += data.programs.size
                    } catch (e: Exception) {
                        if (loadError == null) loadError = e.message ?: e.javaClass.simpleName
                    }
                }
            } catch (e: Exception) {
                if (loadError == null) loadError = e.message ?: e.javaClass.simpleName
            }
            val finalState: EpgLoadState
            if (mergedPrograms.isEmpty()) {
                finalState = EpgLoadState.FAILED
            } else {
                // 修复：子线程中先取 allChannels 快照，避免与主线程 onChannelsLoaded
                // 的赋值产生可见性问题（List 虽不可变，但显式快照更安全）
                val channelsSnapshot = allChannels
                epgPrograms = indexEpgWithChannels(EpgParser.EpgData(mergedNames, mergedPrograms), channelsSnapshot)
                repository.epgProgramCount = totalPrograms
                repository.epgUpdatedAt = System.currentTimeMillis()
                repository.saveEpgCache(mergedNames, mergedPrograms)
                epgLoadedCount = totalPrograms
                finalState = EpgLoadState.READY
            }
            val err = loadError
            runOnUiThread {
                try {
                    epgLoadState = finalState
                    updateNowPlaying()
                    adapter.epgNow = buildEpgNowMap()
                    adapter.epgNext = buildEpgNextMap()
                    updateProgramInfo()
                    updateSourceStatus()
                    if (finalState == EpgLoadState.FAILED) {
                        Toast.makeText(
                            applicationContext,
                            "节目指南加载失败" + (if (err != null) "：" + err else ""),
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (finalState == EpgLoadState.READY && totalPrograms > 0) {
                        Toast.makeText(
                            applicationContext,
                            "节目指南加载成功（" + totalPrograms + " 条节目）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (ignored: Throwable) {
                }
            }
        }.start()
    }

    /** 频道名归一化：小写、去符号空格、去画质后缀（极清/高清/4K…），用于 EPG 模糊匹配 */
    private fun normalizeEpgName(name: String): String {
        var s = name.trim().lowercase(Locale.getDefault())
        s = s.replace(Regex("[^a-z0-9\u4e00-\u9fa5]"), "")
        for (suffix in listOf(
            "极清", "超清", "高清", "标清", "流畅", "原画", "蓝光",
            "4k", "8k", "uhd", "fhd", "hd", "sd", "2160p", "1080p", "720p"
        )) {
            s = s.removeSuffix(suffix)
        }
        return s
    }

    private fun indexEpg(data: EpgParser.EpgData): Map<String, List<EpgProgram>> {
        val map = HashMap<String, List<EpgProgram>>()
        val byName = HashMap<String, MutableList<EpgProgram>>()
        val byNorm = HashMap<String, MutableList<EpgProgram>>()
        for (p in data.programs) {
            byName.getOrPut(p.channelId) { ArrayList() }.add(p)
            byNorm.getOrPut(normalizeEpgName(p.channelId)) { ArrayList() }.add(p)
        }
        for (ch in allChannels) {
            // 1) 精确匹配 tvgId / 频道名
            var list = byName[ch.tvgId] ?: byName[ch.name]
            // 2) 归一化匹配（覆盖"频道名带画质后缀"的常见情况）
            if (list == null) {
                list = byNorm[normalizeEpgName(ch.name)]
            }
            if (list != null) map[ch.id] = list.sortedBy { it.start }
        }
        return map
    }

    /** 带频道快照的 EPG 索引（子线程安全版本） */
    private fun indexEpgWithChannels(data: EpgParser.EpgData, channels: List<Channel>): Map<String, List<EpgProgram>> {
        val map = HashMap<String, List<EpgProgram>>()
        val byName = HashMap<String, MutableList<EpgProgram>>()
        val byNorm = HashMap<String, MutableList<EpgProgram>>()
        for (p in data.programs) {
            byName.getOrPut(p.channelId) { ArrayList() }.add(p)
            byNorm.getOrPut(normalizeEpgName(p.channelId)) { ArrayList() }.add(p)
        }
        for (ch in channels) {
            var list = byName[ch.tvgId] ?: byName[ch.name]
            if (list == null) {
                list = byNorm[normalizeEpgName(ch.name)]
            }
            if (list != null) map[ch.id] = list.sortedBy { it.start }
        }
        return map
    }

    /** 构建"正在播放"节目文本映射（频道列表副行用）。 */
    private fun buildEpgNowMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        val map = HashMap<String, String>()
        for (ch in allChannels) {
            val programs = epgPrograms[ch.id] ?: continue
            val cur = programs.firstOrNull { now in it.start until it.end }
            if (cur != null) {
                val startStr = epgTimeFmt.format(java.util.Date(cur.start))
                val endStr = epgTimeFmt.format(java.util.Date(cur.end))
                map[ch.id] = "▶ ${cur.title}  [$startStr-$endStr]"
            }
        }
        return map
    }

    /** 构建每个频道的下一个节目文本：channelId -> "下一个：xxx [HH:mm-HH:mm]" */
    private fun buildEpgNextMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        val map = HashMap<String, String>()
        for (ch in allChannels) {
            val programs = epgPrograms[ch.id] ?: continue
            val next = programs.firstOrNull { it.start >= now }
            if (next != null) {
                val startStr = epgTimeFmt.format(java.util.Date(next.start))
                val endStr = epgTimeFmt.format(java.util.Date(next.end))
                map[ch.id] = "◷ ${next.title}  [$startStr-$endStr]"
            }
        }
        return map
    }

    /** 更新右侧节目详情栏（EPG 当前节目+进度+描述+今日节目单）。ch 为空时用当前播放频道。 */
    private fun updateProgramInfo(ch: Channel? = null) {
        try {
            val target = ch ?: currentChannel
            if (target == null) {
                binding.tvInfoChannel.text = "未选择频道"
                binding.tvInfoNow.text = "请先选择频道"
                binding.tvInfoNowTime.text = ""
                binding.infoProgressBar.visibility = View.GONE
                binding.tvInfoDesc.text = ""
                epgListAdapter.submitPrograms(emptyList(), null)
                binding.tvInfoMeta.text = ""
                return
            }
            binding.tvInfoChannel.text = target.name
            val programs = epgPrograms[target.id] ?: emptyList()
            val now = System.currentTimeMillis()
            val current = programs.firstOrNull { now in it.start until it.end }
            if (current != null) {
                binding.tvInfoNow.text = current.title
                val startStr = formatTime(current.start)
                val endStr = formatTime(current.end)
                val remainingMin = ((current.end - now) / 60000).toInt()
                val remainingStr = if (remainingMin > 0) "剩余${remainingMin}分钟" else "即将结束"
                binding.tvInfoNowTime.text = "$startStr-$endStr · $remainingStr"
                // 进度条
                val duration = current.end - current.start
                if (duration > 0) {
                    val progress = ((now - current.start) * 100 / duration).toInt().coerceIn(0, 100)
                    binding.infoProgressBar.progress = progress
                    binding.infoProgressBar.visibility = View.VISIBLE
                } else {
                    binding.infoProgressBar.visibility = View.GONE
                }
                // 节目描述
                binding.tvInfoDesc.text = if (current.description.isNotEmpty()) current.description else ""
            } else {
                binding.tvInfoNow.text = when (epgLoadState) {
                    EpgLoadState.NOT_CONFIGURED -> "暂无节目单（请在设置中添加节目指南）"
                    EpgLoadState.LOADING -> "节目指南加载中…"
                    EpgLoadState.FAILED -> "节目指南加载失败"
                    EpgLoadState.READY -> "该时段暂无节目"
                }
                binding.tvInfoNowTime.text = ""
                binding.infoProgressBar.visibility = View.GONE
                binding.tvInfoDesc.text = ""
            }
            // 今日节目单：从当前时间开始，取接下来 12 个节目
            val upcoming = programs.filter { it.end >= now }.take(12)
            epgListAdapter.submitPrograms(upcoming, current?.start)
            // 元信息：分组 + 线路数
            val lineCount = playback.sourceCount()
            binding.tvInfoMeta.text = (target.group ?: "") + if (lineCount > 1) " · ${lineCount}条线路" else ""
        } catch (ignored: Throwable) {
        }
    }

    private fun updateNowPlaying() {
        try {
            val ch = currentChannel
            if (ch == null) {
                binding.tvChannelName.text = getString(R.string.no_channels)
                binding.tvEpgNow.text = getString(R.string.no_epg)
                binding.tvEpgNext.text = ""
                binding.tvChannelMeta.text = ""
                return
            }
            binding.tvChannelName.text = ch.name

            // 序号 / 总数 + 分辨率 + 线路（多线路频道）
            val idx = allChannels.indexOfFirst { it.id == ch.id }
            val meta = StringBuilder()
            if (idx >= 0) {
                meta.append("第 ").append(idx + 1).append(" / ").append(allChannels.size).append(" 频道")
            }
            val srcCount = playback.sourceCount()
            if (srcCount > 1) {
                if (meta.isNotEmpty()) meta.append(" · ")
                meta.append(getString(R.string.line_fmt, playback.currentSourceIndex() + 1, srcCount))
            }
            if (videoW > 0 && videoH > 0) {
                if (meta.isNotEmpty()) meta.append(" · ")
                meta.append("分辨率 ").append(videoW).append("×").append(videoH)
            }
            binding.tvChannelMeta.text = meta.toString()

            val programs = epgPrograms[ch.id] ?: emptyList()
            val now = System.currentTimeMillis()
            val current = programs.firstOrNull { now in it.start until it.end }
            val next = programs.firstOrNull { it.start >= now }
            if (current != null) {
                val startStr = formatTime(current.start)
                val endStr = formatTime(current.end)
                val remainingMin = ((current.end - now) / 60000).toInt()
                val remainingStr = if (remainingMin > 0) "剩余 ${remainingMin} 分钟" else "即将结束"
                binding.tvEpgNow.text = "正在播放: ${current.title}  [$startStr-$endStr]  $remainingStr"
                // 进度条
                val duration = current.end - current.start
                if (duration > 0) {
                    val progress = ((now - current.start) * 100 / duration).toInt().coerceIn(0, 100)
                    binding.epgProgressBar.progress = progress
                    binding.epgProgressBar.visibility = View.VISIBLE
                } else {
                    binding.epgProgressBar.visibility = View.GONE
                }
            } else {
                binding.tvEpgNow.text = getString(R.string.no_epg)
                binding.epgProgressBar.visibility = View.GONE
            }
            binding.tvEpgNext.text =
                if (next != null) "稍后 ${formatTime(next.start)}-${formatTime(next.end)}  ${next.title}" else ""
            updateFavoriteIcon()
        } catch (ignored: Throwable) {
        }
    }

    /** 仅更新 EPG 进度条（每秒调用，不更新文字，避免频繁重绘） */
    private fun updateEpgProgressOnly() {
        try {
            val now = System.currentTimeMillis()
            // 顶部信息栏进度条（当前播放频道）
            val ch = currentChannel
            if (ch != null) {
                val programs = epgPrograms[ch.id]
                if (programs != null) {
                    val current = programs.firstOrNull { now in it.start until it.end }
                    if (current != null) {
                        val duration = current.end - current.start
                        if (duration > 0) {
                            val progress = ((now - current.start) * 100 / duration).toInt().coerceIn(0, 100)
                            binding.epgProgressBar.progress = progress
                            binding.epgProgressBar.visibility = View.VISIBLE
                        }
                    } else {
                        binding.epgProgressBar.visibility = View.GONE
                    }
                }
            }
            // 第三栏节目详情进度条（当前焦点频道，频道列表可见时才更新）
            if (binding.channelPanel.visibility == View.VISIBLE) {
                val focusCh = lastFocusedChannel ?: currentChannel
                if (focusCh != null) {
                    val focusPrograms = epgPrograms[focusCh.id]
                    if (focusPrograms != null) {
                        val focusCurrent = focusPrograms.firstOrNull { now in it.start until it.end }
                        if (focusCurrent != null) {
                            val fduration = focusCurrent.end - focusCurrent.start
                            if (fduration > 0) {
                                val fprogress = ((now - focusCurrent.start) * 100 / fduration).toInt().coerceIn(0, 100)
                                binding.infoProgressBar.progress = fprogress
                                binding.infoProgressBar.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun updateClockVisibility() {
        // 显示时间偏好：false 时右上角不再显示时钟（网速行也随时钟一起隐藏）
        binding.tvClock.visibility = if (repository.showClock) View.VISIBLE else View.GONE
    }

    private fun formatTime(ts: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    }

    // ---------- PlaybackManager.Listener ----------

    override fun onPlaybackReady(channelName: String) {
        try {
            binding.tvChannelName.text = channelName
            updateNowPlaying()
        } catch (ignored: Throwable) {
        }
    }

    override fun onPlaybackError(message: String) {
        try {
            val srcCount = playback.sourceCount()
            binding.tvEpgNow.text =
                if (srcCount > 1) {
                    "线路 ${playback.currentSourceIndex() + 1}/$srcCount: $message"
                } else {
                    "播放失败: $message"
                }
        } catch (ignored: Throwable) {
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        // 直播无播放/暂停按钮，此回调预留
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        try {
            videoW = width
            videoH = height
            updateNowPlaying()
        } catch (ignored: Throwable) {
        }
    }

    // ---------- 遥控器按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 设置面板打开：BACK 关闭；左右键在导航列与详情区之间切换焦点
        if (isSettingsPanelVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideSettingsPanel(); true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 焦点在导航列 -> 进入详情区并定位到当前值
                    if (settingsNavs().any { it.hasFocus() }) {
                        focusValueInSettingsSection(currentSettingsTab)
                        // 如果焦点仍在导航列（详情区无可聚焦项），也算处理完成，避免 super 乱跳
                        true
                    } else {
                        super.onKeyDown(keyCode, event)
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // 焦点在详情区 -> 回到导航列当前项
                    if (settingsSections().any { s -> containsFocus(s) }) {
                        val navs = settingsNavs()
                        navs[currentSettingsTab.coerceIn(0, navs.size - 1)].requestFocus()
                        true
                    } else {
                        super.onKeyDown(keyCode, event)
                    }
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }
        // 频道列表打开：BACK 关闭；左右键在分组/频道间切换焦点
        if (isChannelPanelVisible) {
            if (binding.tvSourceBar.hasFocus()) {
                return when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        hideChannelPanel(); true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        switchToNextSource(); true
                    }
                    else -> super.onKeyDown(keyCode, event)
                }
            }
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideChannelPanel(); true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // 左移：如果焦点在频道列表，切到分组列表
                    if (binding.channelList.hasFocus() || binding.programInfo.hasFocus()) {
                        binding.groupList.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 右移：焦点在分组列表 → 滚动定位到目标频道 → 请求右栏焦点
                    if (binding.groupList.hasFocus()) {
                        val targetPos = if (pendingChannelScrollPos >= 0) {
                            pendingChannelScrollPos
                        } else {
                            currentChannel?.let { adapter.positionOfChannel(it.id) }
                                ?.takeIf { it >= 0 }
                                ?: adapter.firstPositionOfGroup(currentGroup ?: "")
                        }
                        if (targetPos >= 0) {
                            binding.channelList.scrollToPosition(targetPos)
                        }
                        pendingChannelScrollPos = -1
                        binding.channelList.post {
                            if (targetPos >= 0) {
                                val holder = binding.channelList.findViewHolderForAdapterPosition(targetPos)
                                holder?.itemView?.requestFocus()
                            }
                            if (!binding.channelList.hasFocus()) binding.channelList.requestFocus()
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // 频道列表有焦点：OK 立即播放焦点频道并关闭列表（已实时预览则直接关闭）
                    // 分组列表有焦点：OK 进入频道列表
                    if (binding.channelList.hasFocus()) {
                        lastFocusedChannel?.let { ch ->
                            if (currentChannel?.id != ch.id) {
                                currentChannel = ch
                                repository.lastChannelId = ch.id
                                adapter.setSelected(ch.id)
                                playback.play(ch.sources.ifEmpty { listOf(ch.url) }, ch.name)
                                updateNowPlaying()
                                updateLineupLabel()
                            }
                        }
                        hideChannelPanel()
                    } else if (binding.groupList.hasFocus()) {
                        binding.channelList.requestFocus()
                    } else {
                        super.onKeyDown(keyCode, event)
                    }
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }
        // 全屏播放态：任何按键都重新显示覆盖层并重置自动隐藏计时
        showOverlay()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                switchToPrevLine(); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                switchToNextLine(); true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                switchChannel(-1); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                switchChannel(1); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showChannelPanel(); true
            }
            KeyEvent.KEYCODE_MENU -> {
                showSettingsPanel(); true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                switchChannel(1); true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                switchChannel(-1); true
            }
            KeyEvent.KEYCODE_BACK -> {
                // 双击返回退出
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    showExitDialog()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(applicationContext, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** 退出确认弹窗 */
    private fun showExitDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("退出揽星TV")
                .setMessage("确定要退出应用吗？")
                .setPositiveButton("退出") { _, _ ->
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (ignored: Throwable) {
        }
    }

    override fun onDestroy() {
        overlayHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
        previewHandler.removeCallbacksAndMessages(null)
        surfaceResumeHandler.removeCallbacksAndMessages(null)
        try {
            remoteServer?.stop()
        } catch (ignored: Exception) {
        }
        // 修复：必须在 super.onDestroy() 之前释放播放器，
        // 否则 Activity 已销毁后再 release() 可能异常或资源泄漏。
        try {
            playback.release()
        } catch (ignored: Exception) {
        }
        super.onDestroy()
    }

    // ---------- 左侧分组列表 Adapter ----------

    private inner class GroupAdapter(
        private val onGroupClick: (String?) -> Unit,
        private val onGroupFocused: (String?) -> Unit = {}
    ) : RecyclerView.Adapter<GroupAdapter.VH>() {

        private var groups: List<String?> = emptyList()
        private var selected: String? = null

        fun submitGroups(newGroups: List<String?>) {
            groups = newGroups
            notifyDataSetChanged()
        }

        fun setSelected(group: String?) {
            selected = group
            notifyDataSetChanged()
        }

        fun positionOfGroup(group: String?): Int {
            return groups.indexOfFirst { (it == null && group == null) || it == group }
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                id = android.R.id.text1
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(48, 36, 24, 36)
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                // 阻止系统自动焦点搜索：右键交给 Activity.onKeyDown 处理（定位到当前频道）
                nextFocusRightId = View.NO_ID
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val group = groups[position]
            holder.tvName.text = group ?: "全部"
            val isSelected = (selected == null && group == null) || selected == group
            // 选中样式：蓝字（无背景），与焦点亮蓝背景区分
            holder.tvName.setBackgroundColor(0x00000000)
            holder.tvName.setTextColor(
                if (isSelected) 0xFF3D8BFF.toInt() else 0xFFCCCCCC.toInt()
            )
            holder.tvName.paint.isFakeBoldText = isSelected
            // 焦点样式：圆角亮蓝背景 + 白字加粗；焦点移动即切换分组（无需 OK）
            holder.tvName.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                if (focused) {
                    holder.tvName.setBackgroundResource(R.drawable.bg_item_focused)
                    holder.tvName.setTextColor(0xFFFFFFFF.toInt())
                    holder.tvName.paint.isFakeBoldText = true
                    onGroupFocused(group)
                } else {
                    holder.tvName.setBackgroundResource(0)
                    holder.tvName.setTextColor(
                        if (isSelected) 0xFF3D8BFF.toInt() else 0xFFCCCCCC.toInt()
                    )
                    holder.tvName.paint.isFakeBoldText = isSelected
                }
            }
            holder.itemView.setOnClickListener { onGroupClick(group) }
        }

        override fun getItemCount() = groups.size
    }
}