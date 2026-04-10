package com.mixtape.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mixtape.app.databinding.ItemMusicFileBinding

class MusicFileAdapter(
    private val onSelectionChanged: (selectedCount: Int, totalCount: Int) -> Unit
) : RecyclerView.Adapter<MusicFileAdapter.ViewHolder>() {

    private var files: List<MusicFile> = emptyList()
    private val selectedIds = mutableSetOf<Long>()

    fun setFiles(newFiles: List<MusicFile>) {
        files = newFiles
        selectedIds.clear()
        selectedIds.addAll(newFiles.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size, files.size)
    }

    fun getSelectedFiles(): List<MusicFile> =
        files.filter { it.id in selectedIds }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(files.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size, files.size)
    }

    fun deselectAll() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size, files.size)
    }

    val selectedCount: Int get() = selectedIds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMusicFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.bind(file, file.id in selectedIds)
    }

    override fun getItemCount(): Int = files.size

    inner class ViewHolder(
        private val binding: ItemMusicFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: MusicFile, isSelected: Boolean) {
            binding.titleText.text = file.title
            binding.subtitleText.text = "${file.artist} — ${file.album}"
            binding.formatText.text = file.path.substringAfterLast(".").uppercase()
            binding.checkbox.isChecked = isSelected

            val holder = this
            val toggle = {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val id = files[pos].id
                    if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
                    notifyItemChanged(pos)
                    onSelectionChanged(selectedIds.size, files.size)
                }
            }

            binding.checkbox.setOnClickListener { toggle() }
            binding.root.setOnClickListener { toggle() }
        }
    }
}
