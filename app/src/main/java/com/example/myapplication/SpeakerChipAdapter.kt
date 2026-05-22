package com.example.myapplication

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

class SpeakerChipAdapter(
    private val speakers: MutableList<String>,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<SpeakerChipAdapter.SpeakerViewHolder>() {

    private var selectedIndex: Int = 0

    class SpeakerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val dot: View = view.findViewById(R.id.speakerDot)
        val name: TextView = view.findViewById(R.id.speakerName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SpeakerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_speaker_chip, parent, false)
        return SpeakerViewHolder(view).also { holder ->
            holder.card.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
            holder.card.setOnLongClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick(speakers[pos])
                true
            }
        }
    }

    override fun onBindViewHolder(holder: SpeakerViewHolder, position: Int) {
        val subjectName = speakers[position]
        val baseColor = colorForSubject(subjectName)
        val isSelected = position == selectedIndex
        val onSurface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSurface)

        holder.name.text = subjectName
        holder.dot.backgroundTintList = ColorStateList.valueOf(baseColor)
        holder.card.setCardBackgroundColor(
            if (isSelected) ColorUtils.setAlphaComponent(baseColor, 48)
            else Color.TRANSPARENT
        )
        holder.card.strokeColor = if (isSelected) baseColor else Color.parseColor("#33A0AEC0")
        holder.name.setTextColor(if (isSelected) Color.BLACK else onSurface)
    }

    override fun getItemCount(): Int = speakers.size

    fun updateSubjects(newSpeakers: List<String>, newSelectedIndex: Int) {
        val diff = DiffUtil.calculateDiff(SpeakerDiff(speakers.toList(), newSpeakers))
        speakers.clear()
        speakers.addAll(newSpeakers)
        selectedIndex = newSelectedIndex.coerceIn(0, (speakers.size - 1).coerceAtLeast(0))
        diff.dispatchUpdatesTo(this)
    }

    fun setSelectedIndex(index: Int) {
        if (index == selectedIndex) return
        val old = selectedIndex
        selectedIndex = index
        notifyItemChanged(old)
        notifyItemChanged(selectedIndex)
    }

    private class SpeakerDiff(
        private val old: List<String>,
        private val new: List<String>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }

    companion object {
        fun colorForSubject(subjectName: String): Int {
            val palette = listOf(
                Color.parseColor("#06B6D4"),
                Color.parseColor("#2563EB"),
                Color.parseColor("#EF476F"),
                Color.parseColor("#10B981"),
                Color.parseColor("#F59E0B"),
                Color.parseColor("#8B5CF6")
            )
            return palette[kotlin.math.abs(subjectName.hashCode()) % palette.size]
        }
    }
}
