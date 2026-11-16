package com.cyj265.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.cyj265.iptvplayer.R
import com.cyj265.iptvplayer.data.Channel
import com.cyj265.iptvplayer.databinding.ItemChannelBinding
import com.cyj265.iptvplayer.databinding.ItemGroupHeaderBinding

/**
 * 频道列表适配器。
 *
 * 双栏模式（showGroupHeaders=false）：左侧已有独立分组列表，右侧只显示纯频道列表，
 *   每项带全局序号，适合 OK 唤出的左分组右频道双栏布局。
 * 单栏模式（showGroupHeaders=true）：分组头 + 频道项混合在一个列表里，分组可折叠。
 */
class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onCollapsedChanged: (Set<String>) -> Unit = {},
    private val onChannelFocused: (Channel) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
    }

    data class Row(val isHeader: Boolean, val group: String = "", val channel: Channel? = null, val index: Int = 0)

    private val rows = ArrayList<Row>()
    private val collapsedGroups = HashSet<String>()
    private var groups = listOf<String>()
    private val groupCache = HashMap<String, List<Channel>>()

    /** 双栏模式：false=纯频道列表（带序号），true=分组头+频道混合列表 */
    var showGroupHeaders = true
        set(value) {
            field = value
            rebuild()
        }

    /** 用户从未折叠过时默认全部收起（二级列表）。由 MainActivity 按偏好设置。 */
    private var defaultCollapsed = true

    /** 当前 EPG 节目文本：channelId -> "▶ xxx [HH:mm-HH:mm]"（用于频道项副行） */
    var epgNow: Map<String, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    /** 下一个 EPG 节目文本：channelId -> "◷ xxx [HH:mm-HH:mm]"（用于频道项第三行） */
    var epgNext: Map<String, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    var favorites: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }
    private var selectedChannelId: String? = null

    fun setDefaultCollapsed(value: Boolean) {
        defaultCollapsed = value
    }

    /** 恢复折叠状态；collapsed 为空且 defaultCollapsed 时按全收起重建。 */
    fun setCollapsedGroups(collapsed: Set<String>) {
        collapsedGroups.clear()
        collapsedGroups.addAll(collapsed)
        rebuild()
    }

    fun collapsedState(): Set<String> =
        collapsedGroups.toSet()

    fun submitChannels(channels: List<Channel>) {
        groupCache.clear()
        val byGroup = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in channels) {
            val g = ch.group ?: "未分组"
            byGroup.getOrPut(g) { ArrayList() }.add(ch)
        }
        for ((g, list) in byGroup) {
            groupCache[g] = list
        }
        groups = byGroup.keys.toList()
        rebuild()
    }

    private fun rebuild() {
        rows.clear()
        if (!showGroupHeaders) {
            // 双栏模式：纯频道列表，带全局序号
            var idx = 1
            for (g in groups) {
                for (c in groupCache[g] ?: emptyList()) {
                    rows.add(Row(isHeader = false, channel = c, index = idx))
                    idx++
                }
            }
        } else {
            // 单栏模式：分组头 + 频道
            var idx = 1
            for (g in groups) {
                val collapsed = if (collapsedGroups.isEmpty() && defaultCollapsed) {
                    true
                } else {
                    collapsedGroups.contains(g)
                }
                rows.add(Row(isHeader = true, group = g))
                if (!collapsed) {
                    for (c in groupCache[g] ?: emptyList()) {
                        rows.add(Row(isHeader = false, channel = c, index = idx))
                        idx++
                    }
                }
            }
        }
        notifyDataSetChanged()
    }

    fun setSelected(channelId: String?) {
        if (selectedChannelId == channelId) return
        selectedChannelId = channelId
        // 选中态只影响文字颜色，用范围刷新替代全量
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    /** 双栏模式下按全局显示顺序查找频道所在行位置；找不到返回 -1。 */
    fun positionOfChannel(channelId: String): Int {
        for (i in rows.indices) {
            val r = rows[i]
            if (!r.isHeader && r.channel?.id == channelId) return i
        }
        return -1
    }

    /** 返回指定行位置的频道；该行不是频道项时返回 null。 */
    fun channelAt(position: Int): Channel? {
        if (position < 0 || position >= rows.size) return null
        return rows[position].channel
    }

    /** 返回某分组在双栏显示中的第一个频道行位置；找不到返回 -1。 */
    fun firstPositionOfGroup(group: String): Int {
        for (i in rows.indices) {
            val r = rows[i]
            if (!r.isHeader && r.channel?.group == group) return i
        }
        return -1
    }

    override fun getItemViewType(position: Int): Int {
        return if (rows[position].isHeader) TYPE_HEADER else TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            val holder = ChannelHolder(ItemChannelBinding.inflate(inflater, parent, false))
            // 阻止系统自动焦点搜索：左键交给 Activity.onKeyDown 处理（切回分组列表）
            holder.binding.root.nextFocusLeftId = View.NO_ID
            holder
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is HeaderHolder) {
            val count = groupCache[row.group]?.size ?: 0
            val collapsed = if (collapsedGroups.isEmpty() && defaultCollapsed) {
                true
            } else {
                collapsedGroups.contains(row.group)
            }
            holder.binding.groupTitle.text = row.group
            holder.binding.groupCount.text = "$count 个频道"
            holder.binding.groupArrow.text = if (collapsed) "▸" else "▾"
            holder.binding.root.setOnClickListener {
                toggleGroup(row.group)
            }
        } else if (holder is ChannelHolder) {
            val ch = row.channel ?: return
            holder.binding.tvIndex.text = row.index.toString()
            holder.binding.tvChannelName.text = ch.name
            holder.binding.tvEpgLine.text = epgNow[ch.id] ?: ch.group
            val nextText = epgNext[ch.id]
            if (nextText != null && nextText.isNotEmpty()) {
                holder.binding.tvEpgNext.visibility = View.VISIBLE
                holder.binding.tvEpgNext.text = nextText
            } else {
                holder.binding.tvEpgNext.visibility = View.GONE
            }
            // 频道 Logo：有 tvg-logo 则加载网络台标，否则/失败时回退首字母圆形占位。
            // 每次 bind 都显式设定两个 View 的状态，避免 RecyclerView 复用残留。
            val firstChar = ch.name.replace(Regex("[^\\w\\u4e00-\\u9fa5]"), "").firstOrNull()?.toString() ?: "?"
            holder.binding.tvLogoPlaceholder.text = firstChar
            val logoUrl = ch.logo
            if (logoUrl.isNotBlank()) {
                holder.binding.ivChannelLogo.visibility = View.VISIBLE
                holder.binding.tvLogoPlaceholder.visibility = View.GONE
                holder.binding.ivChannelLogo.load(logoUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    listener(onError = { _, _ ->
                        // 台标失效很常见（源地址来自用户 M3U），失败一律回退首字母，不留空白
                        holder.binding.ivChannelLogo.visibility = View.GONE
                        holder.binding.tvLogoPlaceholder.visibility = View.VISIBLE
                    })
                }
            } else {
                holder.binding.ivChannelLogo.visibility = View.GONE
                holder.binding.tvLogoPlaceholder.visibility = View.VISIBLE
            }
            val isFav = favorites.contains(ch.url)
            holder.binding.ivFavorite.visibility = if (isFav) View.VISIBLE else View.GONE
            holder.binding.ivFavorite.setColorFilter(
                if (isFav) ContextCompat.getColor(holder.binding.root.context, R.color.accent) else 0
            )
            holder.binding.root.isSelected = ch.id == selectedChannelId
            // 当前播放频道：蓝字标识（无背景），与焦点亮蓝背景区分
            holder.binding.tvChannelName.setTextColor(
                if (ch.id == selectedChannelId)
                    ContextCompat.getColor(holder.binding.root.context, R.color.accent)
                else
                    ContextCompat.getColor(holder.binding.root.context, R.color.text_primary)
            )
            holder.binding.root.setOnClickListener { onChannelClick(ch) }
            holder.binding.root.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                if (has) onChannelFocused(ch)
            }
        }
    }

    private fun toggleGroup(group: String) {
        if (collapsedGroups.contains(group)) {
            collapsedGroups.remove(group)
        } else {
            collapsedGroups.add(group)
        }
        rebuild()
        onCollapsedChanged(collapsedState())
    }

    class HeaderHolder(val binding: ItemGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    class ChannelHolder(val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root)
}
