package com.play.tube.ui.subscriptions

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.play.tube.R
import com.play.tube.data.ChannelEntry
import com.play.tube.data.LocalStore
import com.play.tube.data.YtRepository
import com.play.tube.databinding.FragmentSubscriptionsBinding
import com.play.tube.ui.channel.ChannelActivity
import com.play.tube.ui.common.SkeletonAdapter
import com.play.tube.ui.common.VideoAdapter
import com.play.tube.ui.common.showSkeleton
import com.play.tube.ui.common.toUiModel
import com.play.tube.ui.player.PlayerActivity
import com.play.tube.util.Formatters
import com.play.tube.util.Net
import com.play.tube.util.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * YouTube-style Subscriptions tab: a row of channel chips on top and, below,
 * the latest videos of the channels you subscribed to (interleaved so every
 * channel shows up). Offline it keeps the skeleton placeholders instead of an error.
 */
class SubscriptionsFragment : Fragment() {

    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private var loadJob: Job? = null
    private var loadedSignature: String? = null
    private var lastLoadedAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubscriptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(requireContext(), model)
        })
        binding.list.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(requireContext(), com.play.tube.util.Layouts.videoSpan(requireContext()))
        com.play.tube.util.HeaderHider(binding.list, binding.chipScroll)
        binding.list.adapter = adapter
        binding.swipe.setOnRefreshListener { load() }
        binding.swipe.setColorSchemeColors(com.play.tube.util.Themes.accentColor(requireContext()))
        binding.swipe.setProgressBackgroundColorSchemeColor(com.play.tube.util.Themes.elevatedColor(requireContext()))
        binding.retryButton.setOnClickListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        refreshIfNeeded()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshIfNeeded()
    }

    /** Called by MainActivity when the connection comes back. */
    fun scrollToTop() {
        _binding?.list?.smoothScrollToPosition(0)
    }

    /** Connection dropped while browsing: replace the stale feed with skeletons. */
    fun onWentOffline() {
        val b = _binding ?: return
        adapter.submitList(emptyList())
        b.swipe.isRefreshing = false
        showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 8)
        lastLoadedAt = 0L
    }

    fun onBackOnline() {
        if (_binding != null) load()
    }

    private fun refreshIfNeeded() {
        if (_binding == null || !isAdded) return
        val subs = LocalStore.subscriptions(requireContext())
        bindChips(subs)
        val stale = SystemClock.elapsedRealtime() - lastLoadedAt > STALE_MS
        if (signatureOf(subs) != loadedSignature || (stale && adapter.currentList.isEmpty())) load()
    }

    private fun signatureOf(subs: List<ChannelEntry>): String = subs.joinToString("|") { it.url }

    /** Channel whose videos fill the feed; null = every subscription mixed. */
    private var selectedChannel: String? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundOutline(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
    }

    /** One story-style item: round logo (red ring when selected) + name below. */
    private fun channelItem(
        label: String, avatarUrl: String?, selected: Boolean, allStyle: Boolean,
        hasNew: Boolean = false, onClick: () -> Unit
    ): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
        val ring = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.bg_avatar_ring)
            isSelected = selected
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
        }
        val inner: View = if (!allStyle && !avatarUrl.isNullOrBlank()) {
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                Thumbs.load(this, avatarUrl)
            }
        } else {
            TextView(ctx).apply {
                setBackgroundResource(R.drawable.bg_avatar_fallback)
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                textSize = 18f
                text = if (allStyle) "" else label.trim().take(1).uppercase()
                if (allStyle) {
                    setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_subscriptions, 0, 0)
                    setPadding(0, dp(12), 0, 0)
                    compoundDrawableTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                }
            }
        }
        roundOutline(inner)
        ring.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (hasNew) {
            // Red dot: this channel posted something since you last opened it.
            ring.addView(View(ctx).apply { setBackgroundResource(R.drawable.bg_new_dot) },
                FrameLayout.LayoutParams(dp(15), dp(15), android.view.Gravity.TOP or android.view.Gravity.END))
        }
        col.addView(ring)
        col.addView(TextView(ctx).apply {
            text = label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            textSize = 11.5f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface))
            if (selected || hasNew) setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4) }
        })
        return col
    }

    private fun bindChips(subs: List<ChannelEntry>) {
        val b = _binding ?: return
        if (selectedChannel != null && subs.none { it.url == selectedChannel }) selectedChannel = null
        b.chipRow.removeAllViews()
        b.chipScroll.isVisible = subs.isNotEmpty()
        b.chipRow.addView(
            channelItem(getString(R.string.subs_all), null, selectedChannel == null, true) {
                if (selectedChannel != null) { selectedChannel = null; load() }
            }
        )
        subs.forEach { channel ->
            b.chipRow.addView(
                channelItem(channel.name, channel.avatarUrl, selectedChannel == channel.url, false,
                    hasNew = hasNew(channel.url)) {
                    // Tap = show only this channel's videos here; tap again = back to all.
                    markSeen(channel.url)
                    selectedChannel = if (selectedChannel == channel.url) null else channel.url
                    load()
                }
            )
        }
    }

    // ----- "New video" dots -----

    /** Newest video url seen in this session's fetch, per channel. */
    private val latestByChannel = HashMap<String, String>()

    private fun seenPrefs() =
        requireContext().getSharedPreferences("playtube_subs_seen", android.content.Context.MODE_PRIVATE)

    private fun hasNew(channelUrl: String): Boolean {
        val latest = latestByChannel[channelUrl] ?: return false
        val seen = seenPrefs().getString(channelUrl, null) ?: return false
        return seen != latest
    }

    private fun markSeen(channelUrl: String) {
        val latest = latestByChannel[channelUrl] ?: return
        seenPrefs().edit().putString(channelUrl, latest).apply()
    }

    /** First time a channel is seen nothing is "new"; afterwards a different newest video is. */
    private fun recordLatest(subs: List<ChannelEntry>, results: List<List<StreamInfoItem>>) {
        val prefs = seenPrefs()
        subs.take(results.size).forEachIndexed { i, channel ->
            val latest = results[i].firstOrNull()?.url ?: return@forEachIndexed
            latestByChannel[channel.url] = latest
            if (prefs.getString(channel.url, null) == null) prefs.edit().putString(channel.url, latest).apply()
        }
    }

    private suspend fun fetchChannel(url: String): List<StreamInfoItem> =
        try {
            YtRepository.channelVideos(url).first.take(PER_CHANNEL)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private fun load() {
        val b = _binding ?: return
        val ctx = requireContext()
        val subs = LocalStore.subscriptions(ctx)
        loadedSignature = signatureOf(subs)
        bindChips(subs)
        loadJob?.cancel()
        b.errorView.isVisible = false
        // Switching filters: drop the previous channel's rows so the skeleton shows.
        adapter.submitList(emptyList())

        if (subs.isEmpty()) {
            b.list.adapter = adapter
            adapter.submitList(emptyList())
            b.emptyView.isVisible = true
            b.swipe.isRefreshing = false
            return
        }
        b.emptyView.isVisible = false

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (adapter.currentList.isEmpty()) {
                    showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 8)
                }
                if (!Net.isOnline(ctx)) {
                    // Offline: keep the skeleton, MainActivity reloads us when the network is back.
                    lastLoadedAt = 0L
                    return@launch
                }
                val only = selectedChannel
                if (only != null) {
                    val items = withContext(Dispatchers.IO) {
                        runCatching { YtRepository.channelVideos(only).first.take(40) }
                            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }
                    }
                    if (_binding !== b) return@launch
                    val models = items.map { it.toUiModel() }
                    b.list.adapter = adapter
                    adapter.submitList(models)
                    lastLoadedAt = SystemClock.elapsedRealtime()
                    b.errorView.isVisible = false
                    if (models.isEmpty()) {
                        b.errorText.text = getString(R.string.error_no_results)
                        b.errorView.isVisible = true
                    }
                    return@launch
                }
                val perChannel = withContext(Dispatchers.IO) {
                    subs.take(MAX_CHANNELS).map { channel ->
                        async { fetchChannel(channel.url) }
                    }.awaitAll()
                }
                if (_binding !== b) return@launch
                recordLatest(subs, perChannel)
                bindChips(subs)
                val merged = ArrayList<StreamInfoItem>()
                for (i in 0 until PER_CHANNEL) {
                    perChannel.forEach { list -> list.getOrNull(i)?.let { merged.add(it) } }
                }
                val models = merged.distinctBy { it.url }.map { it.toUiModel() }
                b.list.adapter = adapter
                adapter.submitList(models)
                lastLoadedAt = SystemClock.elapsedRealtime()
                if (models.isEmpty()) {
                    b.errorText.text = getString(R.string.error_no_results)
                    b.errorView.isVisible = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding !== b) return@launch
                b.list.adapter = adapter
                if (adapter.currentList.isEmpty()) {
                    b.errorText.text = Formatters.friendlyException(e)
                    b.errorView.isVisible = true
                }
                lastLoadedAt = 0L
            } finally {
                if (_binding === b) b.swipe.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val MAX_CHANNELS = 15
        const val PER_CHANNEL = 4
        const val STALE_MS = 5 * 60 * 1000L
    }
}
