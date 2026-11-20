package com.katavu.usage.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.katavu.usage.R
import com.katavu.usage.databinding.ItemRecordingBinding
import com.katavu.usage.upload.RecordingMetadata
import com.katavu.usage.upload.UploadEntry
import com.katavu.usage.upload.UploadStatus

class RecordingsAdapter(
    private val onClick: (UploadEntry) -> Unit,
) : ListAdapter<UploadEntry, RecordingsAdapter.Holder>(Diff) {

    class Holder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        val ctx = holder.itemView.context
        val b = holder.binding
        val meta = entry.meta

        val arrow = if (meta.direction == RecordingMetadata.DIRECTION_IN) "↙" else "↗"
        b.title.text = "$arrow ${meta.phoneNumber ?: "Unknown number"}"

        val parts = mutableListOf(
            DateUtils.formatDateTime(ctx, meta.startedAtMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
            DateUtils.formatElapsedTime(meta.durationSeconds.toLong()),
            meta.audioSource,
        )
        if (entry.localDeleted) parts += "local copy deleted"
        b.subtitle.text = parts.joinToString(" · ")

        val (label, color) = when (entry.status) {
            UploadStatus.PENDING -> R.string.status_pending to R.color.status_pending
            UploadStatus.UPLOADING -> R.string.status_uploading to R.color.status_pending
            UploadStatus.UPLOADED -> R.string.status_uploaded to R.color.status_uploaded
            UploadStatus.FAILED -> R.string.status_failed to R.color.status_failed
        }
        b.status.setText(label)
        b.status.setTextColor(ContextCompat.getColor(ctx, color))

        val error = entry.lastError.takeIf { entry.status != UploadStatus.UPLOADED }
        b.error.visibility = if (error != null) View.VISIBLE else View.GONE
        b.error.text = error?.let { if (entry.attempts > 0) "$it (attempt ${entry.attempts})" else it }

        holder.itemView.setOnClickListener { onClick(entry) }
    }

    private object Diff : DiffUtil.ItemCallback<UploadEntry>() {
        override fun areItemsTheSame(a: UploadEntry, b: UploadEntry) = a.fileName == b.fileName
        override fun areContentsTheSame(a: UploadEntry, b: UploadEntry) = a == b
    }
}
