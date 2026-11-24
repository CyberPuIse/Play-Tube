package com.play.tube.ui.common

import android.content.Intent
import android.view.View
import com.play.tube.R
import com.play.tube.data.LocalStore

/** The "⋮" / long-press menu of a video card, as a premium bottom sheet. */
object VideoMenu {
    fun show(
        anchor: View,
        item: VideoUiModel,
        onPlay: () -> Unit,
        onDownload: (() -> Unit)? = null,
        onRemoveFromList: (() -> Unit)? = null,
        onNotInterested: (() -> Unit)? = null
    ) {
        val ctx = anchor.context
        val saved = LocalStore.isWatchLater(ctx, item.url)
        val fav = LocalStore.isFavorite(ctx, item.url)
        val sheet = PremiumSheet(ctx)
            .title(item.title)
            .message(item.uploader)
            .row(R.drawable.ic_play_arrow, ctx.getString(R.string.cd_play)) { onPlay() }
            .row(
                R.drawable.ic_watch_later,
                ctx.getString(if (saved) R.string.menu_watch_later_remove else R.string.menu_watch_later)
            ) {
                val now = LocalStore.toggleWatchLater(ctx, item.toEntry())
                Snack.show(anchor, ctx.getString(
                    if (now) R.string.snack_watch_later_added else R.string.snack_watch_later_removed
                ), ctx.getString(R.string.snack_undo)) { LocalStore.toggleWatchLater(ctx, item.toEntry()) }
            }
            .row(R.drawable.ic_download, ctx.getString(R.string.action_download)) {
                onDownload?.invoke() ?: VideoDownloadAction.start(ctx, item)
            }
            .row(
                if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                ctx.getString(if (fav) R.string.video_remove_favorite else R.string.action_favorite),
                selected = fav
            ) {
                val added = LocalStore.toggleFavorite(ctx, item.toEntry())
                Snack.show(anchor, ctx.getString(
                    if (added) R.string.added_to_favorites else R.string.removed_from_favorites
                ))
            }
            .row(R.drawable.ic_share, ctx.getString(R.string.action_share)) {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.url)
                }, ctx.getString(R.string.share_via)))
            }
        if (onNotInterested != null) {
            sheet.row(R.drawable.ic_block, ctx.getString(R.string.menu_not_interested)) { onNotInterested() }
        }
        if (onRemoveFromList != null) {
            sheet.row(R.drawable.ic_delete, ctx.getString(R.string.video_remove_from_list), destructive = true) {
                onRemoveFromList()
            }
        }
        sheet.show()
    }
}
