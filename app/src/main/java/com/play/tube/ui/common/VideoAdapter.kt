package com.play.tube.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.play.tube.R
import com.play.tube.databinding.ItemVideoBinding
import com.play.tube.util.Thumbs

class VideoAdapter(
    private val onClick: (VideoUiModel) -> Unit,
    private val onLongClick: ((VideoUiModel) -> Boolean)? = null,
    private val onDownload: ((VideoUiModel) -> Unit)? = null,
    /** Home feed: lets the menu hide a video and teach the recommender. */
    private val allowHide: Boolean = false
) : ListAdapter<VideoUiModel, VideoAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoUiModel>() {
            override fun areItemsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url

            override fun areContentsTheSame(a: VideoUiModel, b: VideoUiModel): Boolean =
                a.url == b.url && a.title == b.title && a.viewsLabel == b.viewsLabel
        }
    }

    class VH(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.title.text = item.title
        val metaParts = listOf(item.uploader, item.viewsLabel, item.uploadDate).filter { it.isNotBlank() }
        b.meta.text = metaParts.joinToString(" • ")

        if (item.durationLabel.isEmpty()) {
            b.duration.visibility = android.view.View.GONE
        } else {
            b.duration.visibility = android.view.View.VISIBLE
            b.duration.text = item.durationLabel
        }

        Thumbs.load(b.thumbnail, item.thumbnailUrl)
        Thumbs.load(b.avatar, item.uploaderAvatarUrl)

        b.overflowButton.contentDescription = b.root.context.getString(R.string.video_more_options, item.title)
        val openMenu = { anchor: android.view.View ->
            VideoMenu.show(
                anchor, item,
                onPlay = { onClick(item) },
                onDownload = onDownload?.let { cb -> { cb(item) } },
                onRemoveFromList = if (onLongClick != null) ({ onLongClick.invoke(item); Unit }) else null,
                onNotInterested = if (allowHide) ({ hide(anchor, item) }) else null
            )
        }
        b.overflowButton.setOnClickListener { openMenu(it) }
        b.root.setOnClickListener { onClick(item) }
        b.root.setOnLongClickListener {
            com.play.tube.util.Haptics.tick(it)
            if (onLongClick != null) onLongClick.invoke(item) else { openMenu(it); true }
        }
    }

    /** Removes the card right away; the snackbar can bring it back. */
    private fun hide(anchor: android.view.View, item: VideoUiModel) {
        val ctx = anchor.context
        val before = currentList
        val index = before.indexOf(item).coerceAtLeast(0)
        com.play.tube.data.RecommendEngine.markNotInterested(ctx, item.url, item.uploader)
        submitList(before.filter { it.url != item.url })
        Snack.show(anchor.rootView, ctx.getString(R.string.snack_hidden), ctx.getString(R.string.snack_undo)) {
            com.play.tube.data.RecommendEngine.undoNotInterested(ctx, item.url, item.uploader)
            val now = currentList.toMutableList()
            now.add(index.coerceAtMost(now.size), item)
            submitList(now)
        }
    }
}
