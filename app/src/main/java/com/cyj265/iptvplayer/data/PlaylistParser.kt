package com.cyj265.iptvplayer.data

import java.util.regex.Pattern

/**
 * 解析 M3U / M3U8 播放列表，或 TXT 播放列表（自动识别）。
 *
 * v1.7.1 起：解析完成后按（分组, 频道名）合并同名单频道为多线路，
 * 播放列表里重复出现的同名频道（如多个 CCTV1 源）只显示一个条目。
 */
object PlaylistParser {

    private val EXTINF_PATTERN = Pattern.compile(
        "#EXTINF:([^,]*),(.*)"
    )

    /**
     * 从 M3U 内容中提取 #EXTM3U 头部的 url-tvg="..." 属性（EPG 节目单地址）。
     * 支持多个 url-tvg（空格分隔）；返回去重后的地址列表，找不到返回空列表。
     */
    fun extractEpgUrls(content: String): List<String> {
        try {
            for (rawLine in content.split("\n", "\r\n")) {
                val line = rawLine.trim()
                if (!line.startsWith("#EXTM3U")) continue
                val urls = LinkedHashSet<String>()
                val m = Pattern.compile("url-tvg\\s*=\\s*\"([^\"]*)\"").matcher(line)
                while (m.find()) {
                    val value = m.group(1)?.trim() ?: ""
                    if (value.isNotEmpty() && value.startsWith("http")) urls.add(value)
                }
                return urls.toList()
            }
        } catch (ignored: Exception) {
        }
        return emptyList()
    }

    fun parseAuto(content: String): List<Channel> {
        val trimmed = content.trimStart()
        val raw = if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXTINF")) {
            parseRaw(content)
        } else {
            parseTxtRaw(content)
        }
        return mergeChannels(raw)
    }

    /**
     * 解析 TXT 播放列表。常见格式：
     *  频道名,http://...
     *  频道名，http://...（中文逗号）
     *  纯 URL 行（自动用序号命名）
     */
    private fun parseTxtRaw(content: String): List<Channel> {
        val channels = ArrayList<Channel>()
        var index = 0
        for (raw in content.split("\n", "\r\n")) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
