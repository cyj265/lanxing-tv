package com.cyj265.iptvplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cyj265.iptvplayer.data.EpgProgram
import com.cyj265.iptvplayer.databinding.ItemEpgProgramBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 节目单列表适配器（第三栏今日节目单）。
 */
class EpgListAdapter : RecyclerView.Adapter<EpgListAdapter.EpgViewHolder>() {

    private val programs = ArrayList<EpgProgram>()
    private var currentProgramId: String? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitPrograms(list: List<EpgProgram>, currentStart: Long?) {
        programs.clear()
        programs.addAll(list)
        currentProgramId = if (currentStart != null) "epg_$currentStart" else null
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpgViewHolder {
        val binding = ItemEpgProgramBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EpgViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpgViewHolder, position: Int) {
        val p = programs[position]
        val isCurrent = currentProgramId == "epg_${p.start}"
        holder.bind(p, isCurrent, timeFormat)
    }

    override fun getItemCount(): Int = programs.size

    class EpgViewHolder(private val binding: ItemEpgProgramBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(program: EpgProgram, isCurrent: Boolean, timeFormat: SimpleDateFormat) {
            binding.tvEpgTime.text = timeFormat.format(Date(program.start))
            binding.tvEpgTitle.text = program.title
            if (isCurrent) {
                binding.tvEpgTime.setTextColor(0xFF4C9DFF.toInt())
                binding.tvEpgTitle.setTextColor(0xFFFFFFFF.toInt())
                binding.root.setBackgroundColor(0x1A4C9DFF.toInt())
            } else {
                binding.tvEpgTime.setTextColor(0xFF8FA0BC.toInt())
                binding.tvEpgTitle.setTextColor(0xFFC8CDD6.toInt())
                binding.root.setBackgroundColor(0x00000000)
            }
        }
    }
}
