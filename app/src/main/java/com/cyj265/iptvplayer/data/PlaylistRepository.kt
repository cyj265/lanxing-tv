package com.cyj265.iptvplayer.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 播放列表与偏好存储。
 *
 * v1.7.0 起支持多个直播源：
 * - sources_json：全部源 URL 列表（JSON 数组，保序）；
 * - active_source_index：当前使用的源序号；
 * - 每个源独立的频道缓存文件（playlist_cache_<hash>.json）与最后更新时间。
 * 旧版单源数据（playlist_url）首次读取时自动迁移为首个源。
 *
 * 所有 SharedPreferences 读取都带异常兜底：盒子存储异常导致 SP 文件损坏时，
 * 自动重置为空数据，保证应用能正常启动（否则会"启动即崩、打不开"）。
 */
class PlaylistRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)

    /** 源健康度内存缓存（key=源URL，value=健康度） */
    private val sourceHealthCache = mutableMapOf<String, SourceHealthChecker.SourceHealth>()

    /** 自动优选最快源开关 */
    var autoSelectFastest: Boolean
        get() = safeGetBoolean("auto_select_fastest", false)
        set(value) = safeApply { putBoolean("auto_select_fastest", value) }

    private fun safeGetString(key: String, def: String?): String? {
        return try {
            prefs.getString(key, def)
        } catch (e: Throwable) {
            resetPrefs()
            def
        }
    }

    private fun safeGetBoolean(key: String, def: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, def)
        } catch (e: Throwable) {
            resetPrefs()
            def
        }
    }

    private fun safeGetLong(key: String, def: Long): Long {
        return try {
            prefs.getLong(key, def)
        } catch (e: Throwable) {
            resetPrefs()
            def
        }
    }

    private fun safeGetInt(key: String, def: Int): Int {
        return try {
            prefs.getInt(key, def)
        } catch (e: Throwable) {
            resetPrefs()
            def
        }
    }

    private fun safeGetStringSet(key: String): MutableSet<String> {
        return try {
            prefs.getStringSet(key, HashSet())!!.toMutableSet()
        } catch (e: Throwable) {
            resetPrefs()
            HashSet()
        }
    }

    private fun resetPrefs() {
        try {
            prefs.edit().clear().commit()
        } catch (ignored: Exception) {
        }
    }

    private fun safeApply(block: SharedPreferences.Editor.() -> Unit) {
        try {
            val editor = prefs.edit()
            block(editor)
            editor.apply()
        } catch (ignored: Exception) {
        }
    }

    // ---------- 多直播源 ----------

    /** 全部直播源 URL 列表（保序）。旧版单源自动迁移。 */
    fun getSources(): List<String> {
        try {
            val json = safeGetString("sources_json", null)
            if (json.isNullOrBlank()) {
                val old = safeGetString("playlist_url", null)
                if (!old.isNullOrBlank()) {
                    saveSources(listOf(old.trim()))
                    return listOf(old.trim())
                }
                return emptyList()
            }
            val arr = JSONArray(json)
            return (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun saveSources(sources: List<String>) {
        val arr = JSONArray()
        for (s in sources) arr.put(s)
        safeApply { putString("sources_json", arr.toString()) }
    }

    fun addSource(url: String): Boolean {
        val cur = getSources().toMutableList()
        if (cur.contains(url)) return false
        cur.add(url)
        saveSources(cur)
        return true
    }

    /** 删除指定源，返回被删除的 URL（null 表示越界）。 */
    fun removeSource(index: Int): String? {
        val cur = getSources().toMutableList()
        if (index < 0 || index >= cur.size) return null
        val removed = cur.removeAt(index)
        saveSources(cur)
        val curIdx = activeSourceIndex
        when {
            cur.isEmpty() -> activeSourceIndex = 0
            // 删除当前源之前的源：当前源索引前移
            index < curIdx -> activeSourceIndex = (curIdx - 1).coerceIn(0, cur.size - 1)
            // 删除当前源或之后的源：索引可能越界则修正
            curIdx >= cur.size -> activeSourceIndex = cur.size - 1
        }
        deleteCacheFor(removed)
        return removed
    }

    /** 当前源序号 */
    var activeSourceIndex: Int
        get() {
            val v = safeGetLong("active_source_index", 0).toInt()
            val size = getSources().size
            return if (size == 0) 0 else v.coerceIn(0, size - 1)
        }
        set(value) = safeApply { putLong("active_source_index", value.toLong()) }

    /** 当前源 URL（null = 无源） */
    fun getActiveSource(): String? {
        val s = getSources()
        return s.getOrNull(activeSourceIndex)
    }

    /** 切换到下一个源，返回切换后的源 URL。 */
    fun switchToNextSource(): String? {
        val s = getSources()
        if (s.isEmpty()) return null
        activeSourceIndex = (activeSourceIndex + 1) % s.size
        return getActiveSource()
    }

    /** 切换到上一个源，返回切换后的源 URL。 */
    fun switchToPrevSource(): String? {
        val s = getSources()
        if (s.isEmpty()) return null
        activeSourceIndex = (activeSourceIndex - 1 + s.size) % s.size
        return getActiveSource()
    }

    // ---------- 源健康度 ----------

    /** 设置某个源的健康度 */
    fun setSourceHealth(url: String, health: SourceHealthChecker.SourceHealth) {
        sourceHealthCache[url] = health
    }

    /** 获取某个源的健康度（未检测返回 UNTESTED） */
    fun getSourceHealth(url: String): SourceHealthChecker.SourceHealth {
        return sourceHealthCache[url] ?: SourceHealthChecker.SourceHealth(
            url = url,
            available = false,
            latencyMs = -1,
            status = SourceHealthChecker.SourceHealth.Status.UNTESTED
        )
    }

    /** 获取全部源的健康度列表（按源顺序） */
    fun getAllSourceHealth(): List<SourceHealthChecker.SourceHealth> {
        return getSources().map { getSourceHealth(it) }
    }

    /** 清除所有源健康度缓存 */
    fun clearSourceHealth() {
        sourceHealthCache.clear()
    }

    // ---------- EPG ----------

    /**
     * EPG 节目指南地址列表（v1.7.1 起支持每行一个、多个地址，全部加载合并）。
     * 旧版单值 epg_url 自动迁移。
     */
    fun getEpgUrls(): List<String> {
        try {
            val json = safeGetString("epg_urls_json", null)
            if (json.isNullOrBlank()) {
                val old = safeGetString("epg_url", null)
                if (!old.isNullOrBlank()) {
                    setEpgUrls(listOf(old.trim()))
                    return listOf(old.trim())
                }
                return emptyList()
            }
            val arr = JSONArray(json)
            return (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun setEpgUrls(urls: List<String>) {
        val arr = JSONArray()
        for (u in urls) if (u.isNotBlank()) arr.put(u.trim())
        safeApply { putString("epg_urls_json", arr.toString()) }
    }

    /** 兼容旧代码读取单个 EPG 地址（返回第一个或 null） */
    var epgUrl: String?
        get() = getEpgUrls().firstOrNull()
        set(value) = setEpgUrls(if (value.isNullOrBlank()) emptyList() else listOf(value))

    /** EPG 上次成功加载的节目条数（0 = 未加载成功） */
    var epgProgramCount: Int
        get() = safeGetLong("epg_program_count", 0).toInt()
        set(value) = safeApply { putLong("epg_program_count", value.toLong()) }

    /** EPG 上次成功加载时间戳 */
    var epgUpdatedAt: Long
        get() = safeGetLong("epg_updated_at", 0)
        set(value) = safeApply { putLong("epg_updated_at", value) }

    /** 节目单（EPG）显示开关（默认开） */
    var epgEnabled: Boolean
        get() = safeGetBoolean("epg_enabled", true)
        set(value) = safeApply { putBoolean("epg_enabled", value) }

    /** 节目单刷新频率（小时，默认 2；24 = 每天） */
    var epgRefreshHours: Int
        get() = safeGetLong("epg_refresh_hours", 2).toInt()
        set(value) = safeApply { putLong("epg_refresh_hours", value.toLong()) }

    /** 清除节目单本地缓存（v1.14.0） */
    fun clearEpgCache() {
        try {
            val f = File(context.filesDir, "epg_cache.json")
            if (f.exists()) f.delete()
            epgUpdatedAt = 0
            epgProgramCount = 0
        } catch (ignored: Throwable) {}
    }

    // ---------- 播放偏好 ----------

    /** 画面比例：fit / fill / zoom / 16:9 / 4:3 */
    var aspectRatio: String
        get() = safeGetString("aspect_ratio", "fill") ?: "fill"
        set(value) = safeApply { putString("aspect_ratio", value) }

    /** 打开应用时自动恢复上次频道 */
    var autoResume: Boolean
        get() = safeGetBoolean("auto_resume", true)
        set(value) = safeApply { putBoolean("auto_resume", value) }

    /** 顶部信息条/底部控制条无操作自动隐藏 */
    var autoHideOverlay: Boolean
        get() = safeGetBoolean("auto_hide_overlay", true)
        set(value) = safeApply { putBoolean("auto_hide_overlay", value) }

    /** 定时关机：0=关闭，30/60/90=分钟数 */
    var sleepTimerMinutes: Int
        get() = safeGetInt("sleep_timer_minutes", 0)
        set(value) = safeApply { putInt("sleep_timer_minutes", value) }

    /** 开机自启动 */
    var autoBoot: Boolean
        get() = safeGetBoolean("auto_boot", false)
        set(value) = safeApply { putBoolean("auto_boot", value) }

    /** 上次播放的频道 id */
    var lastChannelId: String?
        get() = safeGetString("last_channel_id", null)
        set(value) = safeApply { putString("last_channel_id", value) }

    /** 自动换源超时秒数（v1.7.1：5/10/15/20/25/30/60，默认 10） */
    var switchTimeoutSec: Int
        get() {
            val v = safeGetLong("switch_timeout_sec", 10).toInt()
            return if (v in setOf(5, 10, 15, 20, 25, 30, 60)) v else 10
        }
        set(value) = safeApply { putLong("switch_timeout_sec", value.toLong()) }

    /** 偏好：顶部显示时间 */
    var showClock: Boolean
        get() = safeGetBoolean("show_clock", true)
        set(value) = safeApply { putBoolean("show_clock", value) }

    /** 偏好：顶部显示网速 */
    var showSpeed: Boolean
        get() = safeGetBoolean("show_speed", false)
        set(value) = safeApply { putBoolean("show_speed", value) }

    /** 偏好：换台反转（上下方向键逻辑反转） */
    var reverseZap: Boolean
        get() = safeGetBoolean("reverse_zap", false)
        set(value) = safeApply { putBoolean("reverse_zap", value) }

    /** 偏好：跨选分类（换台时允许跨越分类边界；关闭则在当前分组内循环） */
    var crossCategory: Boolean
        get() = safeGetBoolean("cross_category", true)
        set(value) = safeApply { putBoolean("cross_category", value) }

    // ---------- 收藏 ----------

    fun getFavorites(): MutableSet<String> = safeGetStringSet("favorites")

    fun setFavorites(favorites: Set<String>) {
        safeApply { putStringSet("favorites", favorites) }
    }

    // ---------- 分组折叠状态 ----------

    /** 用户是否手动折叠过分组（未折叠过时默认全部收起，二级列表体验） */
    fun hasCollapsedPrefs(): Boolean {
        return try {
            prefs.contains("collapsed_groups")
        } catch (e: Throwable) {
            false
        }
    }

    fun getCollapsedGroups(): MutableSet<String> = safeGetStringSet("collapsed_groups")

    fun saveCollapsedGroups(groups: Set<String>) {
        safeApply { putStringSet("collapsed_groups", groups) }
    }

    // ---------- 频道缓存（每源独立） ----------

    private fun cacheFileFor(url: String): File {
        val hash = url.hashCode().toString(16)
        return File(context.cacheDir, "playlist_cache_$hash.json")
    }

    /** 记录某源最后成功更新时间戳 */
    fun markSourceUpdated(url: String) {
        safeApply { putLong("updated_at_" + url.hashCode().toString(16), System.currentTimeMillis()) }
    }

    fun getSourceUpdatedAt(url: String): Long {
        return safeGetLong("updated_at_" + url.hashCode().toString(16), 0)
    }

    fun saveChannels(channels: List<Channel>, sourceUrl: String) {
        try {
            cacheFileFor(sourceUrl).writeText(toJsonArray(channels), Charsets.UTF_8)
            markSourceUpdated(sourceUrl)
        } catch (ignored: Exception) {
        }
    }

    fun loadCachedChannels(sourceUrl: String): List<Channel>? {
        return try {
            val f = cacheFileFor(sourceUrl)
            if (!f.exists()) return null
            fromJsonArray(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun deleteCacheFor(url: String) {
        try {
            cacheFileFor(url).delete()
        } catch (ignored: Exception) {
        }
    }

    // ---------- 本地文件导入缓存（不算直播源，重启后仍在） ----------
    // 用 filesDir 而非 cacheDir：cacheDir 可能被系统在存储空间不足时清理，
    // 导致用户手动导入的本地频道丢失。

    private val localCacheFile: File
        get() = File(context.filesDir, "playlist_cache_local.json")

    fun saveLocalChannels(channels: List<Channel>) {
        try {
            localCacheFile.writeText(toJsonArray(channels), Charsets.UTF_8)
        } catch (ignored: Exception) {
        }
    }

    fun loadLocalChannels(): List<Channel>? {
        return try {
            val f = localCacheFile
            if (!f.exists()) {
                // 兼容 v1.6.4 及更早的旧缓存文件（playlist_cache.json）
                val legacy = File(context.filesDir, "playlist_cache.json")
                if (legacy.exists()) {
                    return fromJsonArray(legacy.readText(Charsets.UTF_8))
                }
                return null
            }
            fromJsonArray(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    // ---------- EPG 缓存（持久化，重启后按时间线直接显示，不用每次重新下载） ----------

    private val epgCacheFile: File
        get() = File(context.filesDir, "epg_cache.json")

    /** 保存 EPG 数据缓存（节目单 + 频道名映射） */
    fun saveEpgCache(channelNames: Map<String, String>, programs: List<EpgProgram>) {
        try {
            val arr = JSONArray()
            for (p in programs) {
                arr.put(
                    JSONObject()
                        .put("c", p.channelId)
                        .put("s", p.start)
                        .put("e", p.end)
                        .put("t", p.title)
                        .put("d", p.description)
                )
            }
            val names = JSONObject()
            for ((k, v) in channelNames) names.put(k, v)
            epgCacheFile.writeText(
                JSONObject().put("names", names).put("programs", arr).toString(),
                Charsets.UTF_8
            )
        } catch (ignored: Exception) {
        }
    }

    /** 读取 EPG 缓存；无缓存或损坏返回 null */
    fun loadEpgCache(): EpgParser.EpgData? {
        return try {
            val f = epgCacheFile
            if (!f.exists()) return null
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val names = HashMap<String, String>()
            val namesObj = root.optJSONObject("names") ?: JSONObject()
            val it = namesObj.keys()
            while (it.hasNext()) {
                val k = it.next()
                names[k] = namesObj.optString(k)
            }
            val arr = root.optJSONArray("programs") ?: JSONArray()
            val programs = ArrayList<EpgProgram>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                programs.add(
                    EpgProgram(
                        channelId = o.optString("c"),
                        start = o.optLong("s"),
                        end = o.optLong("e"),
                        title = o.optString("t"),
                        description = o.optString("d")
                    )
                )
            }
            if (programs.isEmpty()) null else EpgParser.EpgData(names, programs)
        } catch (e: Exception) {
            null
        }
    }

    /** 频道列表 → JSON 数组（含多线路 sources，旧字段 url 保留） */
    private fun toJsonArray(channels: List<Channel>): String {
        val arr = JSONArray()
        for (c in channels) {
            val srcArr = JSONArray()
            val sources = if (c.sources.isEmpty()) listOf(c.url) else c.sources
            for (s in sources) srcArr.put(s)
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("url", c.url)
                    .put("group", c.group)
                    .put("logo", c.logo)
                    .put("tvgId", c.tvgId)
                    .put("sources", srcArr)
            )
        }
        return arr.toString()
    }

    /** JSON 数组 → 频道列表（兼容无 sources 的旧缓存） */
    private fun fromJsonArray(text: String): List<Channel>? {
        val arr = JSONArray(text)
        val list = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("url")
            val sources = try {
                val sa = o.optJSONArray("sources")
                if (sa != null && sa.length() > 0) {
                    (0 until sa.length()).map { sa.getString(it).trim() }.filter { it.isNotEmpty() }
                } else listOf(url)
            } catch (e: Exception) {
                listOf(url)
            }
            list.add(
                Channel(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = url,
                    group = o.optString("group"),
                    logo = o.optString("logo"),
                    tvgId = o.optString("tvgId"),
                    sources = sources
                )
            )
        }
        return list
    }
}
