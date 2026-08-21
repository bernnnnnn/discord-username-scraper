package com.noctra.scout

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.noctra.scout.databinding.RowResultBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flat, allocation-light adapter. Rows are plain text views, so scrolling stays smooth even
 * with a very long Tried list.
 */
class ResultAdapter(
    private val onClick: (Entry) -> Unit
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    private val items = ArrayList<Entry>()
    private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    init {
        setHasStableIds(true)
    }

    class VH(val binding: RowResultBinding) : RecyclerView.ViewHolder(binding.root)

    fun replaceAll(newItems: List<Entry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun append(newItems: List<Entry>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun getItemId(position: Int): Long = items[position].name.hashCode().toLong()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = RowResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val b = holder.binding
        b.name.text = entry.name
        b.time.text = timeFmt.format(Date(entry.ts))
        if (entry.available) {
            b.dot.setBackgroundResource(R.drawable.dot_available)
            b.status.text = holder.itemView.context.getString(R.string.status_free)
            b.status.setTextColor(COLOR_FREE)
            b.name.setTextColor(COLOR_FREE_NAME)
        } else {
            b.dot.setBackgroundResource(R.drawable.dot_taken)
            b.status.text = holder.itemView.context.getString(R.string.status_taken)
            b.status.setTextColor(COLOR_TAKEN)
            b.name.setTextColor(COLOR_TAKEN_NAME)
        }
        b.root.setOnClickListener { onClick(entry) }
    }

    override fun onViewRecycled(holder: VH) {
        holder.binding.root.setOnClickListener(null)
        super.onViewRecycled(holder)
    }

    private companion object {
        val COLOR_FREE = 0xFFD8E8FF.toInt()
        val COLOR_FREE_NAME = 0xFFFFFFFF.toInt()
        val COLOR_TAKEN = 0xFF69788D.toInt()
        val COLOR_TAKEN_NAME = 0xFFB8C2CD.toInt()
    }
}
