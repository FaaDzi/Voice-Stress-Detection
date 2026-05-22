package com.example.myapplication

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class SegmentsAdapter(
    initialSegments: List<AudioSegment>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<SegmentsAdapter.SegmentViewHolder>() {

    private val segments = initialSegments.toMutableList()

    class SegmentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val accent: View = view.findViewById(R.id.segmentAccent)
        val badge: TextView = view.findViewById(R.id.segmentBadgeText)
        val title: TextView = view.findViewById(R.id.segmentTitle)
        val subtitle: TextView = view.findViewById(R.id.segmentSubtitle)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteSegmentButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SegmentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_segment, parent, false)
        return SegmentViewHolder(view).also { holder ->
            holder.deleteButton.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) onDelete(pos)
            }
        }
    }

    override fun onBindViewHolder(holder: SegmentViewHolder, position: Int) {
        val item = segments[position]
        val color = SpeakerChipAdapter.colorForSubject(item.subjectName)
        holder.accent.backgroundTintList = ColorStateList.valueOf(color)
        holder.badge.text = item.subjectName
            .split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .take(2)
            .ifBlank { "S" }
        holder.badge.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(color, 48)
        )
        holder.badge.setTextColor(color)
        holder.title.text = item.subjectName
        holder.subtitle.text = "${item.label()} • ${item.sourceFileName}"
    }

    override fun getItemCount(): Int = segments.size

    fun replaceAll(newSegments: List<AudioSegment>) {
        val diff = DiffUtil.calculateDiff(SegmentDiff(segments.toList(), newSegments))
        segments.clear()
        segments.addAll(newSegments)
        diff.dispatchUpdatesTo(this)
    }

    private class SegmentDiff(
        private val old: List<AudioSegment>,
        private val new: List<AudioSegment>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].sourceFileName == new[newPos].sourceFileName &&
            old[oldPos].startTimeMs == new[newPos].startTimeMs &&
            old[oldPos].subjectName == new[newPos].subjectName
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }
}
