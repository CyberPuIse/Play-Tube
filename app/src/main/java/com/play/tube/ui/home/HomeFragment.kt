package com.play.tube.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.play.tube.R
import com.play.tube.data.Countries
import com.play.tube.data.RecommendEngine
import com.play.tube.data.YtRepository
import com.play.tube.databinding.FragmentHomeBinding
import com.play.tube.ui.common.SkeletonAdapter
import com.play.tube.ui.common.showSkeleton
import com.play.tube.ui.common.toUiModel
import com.play.tube.ui.common.VideoAdapter
import com.play.tube.ui.player.PlayerActivity
import com.play.tube.ui.search.SearchActivity
import com.play.tube.util.AppPrefs
import com.play.tube.util.CrashReporter
import com.play.tube.util.Formatters
import com.play.tube.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoAdapter
    private var loadedCountry: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    @Volatile private var feedGeneration = 0L
    private var visibleCountry: String? = null

    /**
     * Elapsed-realtime stamp of the last successful feed load. The feed now
     * refreshes on a schedule (see maybeReload + the ticker in onViewCreated),
     * usage: every search / video play used to bump the recommendation
     * generation and silently reload the whole list the moment the user came
     * back — scrolling position lost, skeletons flashing — for no visible
     * benefit.
     */
    private var lastLoadedAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(requireContext(), model)
        }, allowHide = true)
        binding.list.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(requireContext(), com.play.tube.util.Layouts.videoSpan(requireContext()))
        com.play.tube.util.HeaderHider(binding.list, binding.homeHeader)
        bindCategoryChips()
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { load() }
        binding.swipe.setColorSchemeColors(com.play.tube.util.Themes.accentColor(requireContext()))
        binding.swipe.setProgressBackgroundColorSchemeColor(com.play.tube.util.Themes.elevatedColor(requireContext()))

        binding.searchButton.setOnClickListener {
            startActivity(
                SearchActivity.intent(requireContext(), music = false)
            )
        }

        binding.retryButton.setOnClickListener { load() }

        // One automatic refresh every 5 minutes while the feed is on screen.
        // (The user can always pull-to-refresh manually.)
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_MS)
                if (_binding != null && isAdded && !isHidden && stale()) {
                    load()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        maybeReload()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            maybeReload()
        }
    }

    /** True when the feed content is older than the auto-refresh interval. */
    private fun stale(): Boolean =
        lastLoadedAt == 0L || SystemClock.elapsedRealtime() - lastLoadedAt >= AUTO_REFRESH_MS

    /**
     * Reload only when the country changed (different trending pool) or the
     * feed is stale (> 5 min). Coming back from a search or the player does
     * NOT reload anything anymore.
     */
    private fun maybeReload() {
        val current = AppPrefs.countryOrDefault
        if (current != loadedCountry || stale()) {
            load()
        }
    }

    private fun load() {
        val country = AppPrefs.countryOrDefault
        loadedCountry = country
        val generation = ++feedGeneration
        loadJob?.cancel()
        // Capture the binding: a theme change relaunches the activity, which
        // destroys this view (nulling _binding) while the coroutine may still
        // be suspended on IO. Touching `binding` after that crashed the app
        // (NullPointerException in the finally block), so the coroutine works
        // on its own stable reference instead.
        val b = _binding ?: return
        val ctx = requireContext()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            b.errorView.isVisible = false
            b.emptyView.isVisible = false
            b.list.adapter = adapter
            if (visibleCountry != null && visibleCountry != country) {
                adapter.submitList(emptyList())
                visibleCountry = null
            }
            val cat = category
            if (cat != null && Net.isOnline(ctx)) {
                // Category chip: plain video search for that topic.
                adapter.submitList(emptyList())
                visibleCountry = null
                showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 10)
                try {
                    val r = YtRepository.searchVideos(cat)
                    if (_binding !== b || generation != feedGeneration) return@launch
                    val models = r.items.filter { it.duration !in 1..75L }.map { it.toUiModel() }
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
                    if (_binding !== b || generation != feedGeneration) return@launch
                    b.list.adapter = adapter
                    b.errorText.text = Formatters.friendlyException(e)
                    b.errorView.isVisible = true
                    lastLoadedAt = 0L
                } finally {
                    if (_binding === b && generation == feedGeneration) b.swipe.isRefreshing = false
                }
                return@launch
            }
            if (!Net.isOnline(ctx)) {
                // Offline: no cached feed at all, go straight to skeletons (like YouTube).
                adapter.submitList(emptyList())
                visibleCountry = null
                b.swipe.isRefreshing = false
                showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 10)
                lastLoadedAt = 0L
                return@launch
            }
            b.swipe.isRefreshing = visibleCountry == country && adapter.currentList.isNotEmpty()
            try {
                // Disk access is off-main. A cached list is installed before any network work.
                val cached = withContext(Dispatchers.IO) { HomeFeedCache.read(ctx, country) }
                if (!isCurrent(b, country, generation)) return@launch
                if (cached.isNotEmpty()) {
                    adapter.submitList(cached)
                    visibleCountry = country
                } else if (visibleCountry != country || adapter.currentList.isEmpty()) {
                    showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 10)
                }
                if (!Net.isOnline(ctx)) {
                    // Offline: keep (or show) skeleton placeholders like YouTube;
                    // MainActivity shows the offline popup and calls onBackOnline().
                    if (cached.isEmpty() && adapter.currentList.isEmpty()) {
                        showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 10)
                    }
                    lastLoadedAt = 0L
                    return@launch
                }
                val snap = withContext(Dispatchers.IO) {
                    RecommendEngine.snapshot(ctx)
                }
                val items = YtRepository.personalizedFeed(country, snap, onInitialItems = { initial ->
                    val preview = initial.map { it.toUiModel() }
                    withContext(Dispatchers.Main) {
                        if (preview.isNotEmpty() && (visibleCountry != country || adapter.currentList.isEmpty()) && isCurrent(b, country, generation)) {
                            b.list.adapter = adapter
                            adapter.submitList(preview)
                            visibleCountry = country
                        }
                    }
                })
                if (!isCurrent(b, country, generation)) return@launch
                val models = items.map { it.toUiModel() }
                b.list.adapter = adapter
                if (models.isNotEmpty()) {
                    adapter.submitList(models)
                    visibleCountry = country
                    lastLoadedAt = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.IO) {
                        if (isActive && generation == feedGeneration && AppPrefs.countryOrDefault == country) {
                            HomeFeedCache.write(ctx, country, models, shouldWrite = {
                                generation == feedGeneration && AppPrefs.countryOrDefault == country
                            })
                        }
                    }
                } else if (adapter.currentList.isNotEmpty()) {
                    // An empty network result must not erase a useful saved feed.
                    b.emptyView.isVisible = false
                }
                b.emptyView.isVisible = items.isEmpty() && adapter.currentList.isEmpty()
                if (items.isEmpty() && adapter.currentList.isEmpty()) {
                    b.errorText.text = getString(R.string.error_no_results)
                    b.errorView.isVisible = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A newer load() cancelled this one: not an error.
                throw e
            } catch (e: Exception) {
                if (!isCurrent(b, country, generation)) return@launch
                b.list.adapter = adapter
                if (adapter.currentList.isEmpty()) {
                    b.errorText.text = Formatters.friendlyException(e)
                    b.errorView.isVisible = true
                }
                // Mark as "never loaded" so the next return to this screen
                // retries automatically instead of waiting out the 5 min.
                lastLoadedAt = 0L
            } finally {
                if (_binding === b && generation == feedGeneration) b.swipe.isRefreshing = false
            }
        }
    }

    /** Connection is back: load the feed if it is empty or out of date. */
    /** Active category chip; null = the personalized "All" feed. */
    private var category: String? = null

    private val categories = listOf(
        R.string.cat_all to null,
        R.string.cat_bangla to "bangla new video",
        R.string.cat_music to "music video",
        R.string.cat_gaming to "gaming",
        R.string.cat_news to "news today",
        R.string.cat_comedy to "comedy",
        R.string.cat_sports to "sports highlights",
        R.string.cat_cooking to "cooking recipe",
        R.string.cat_tech to "tech review",
        R.string.cat_movies to "movie scenes"
    )

    private fun bindCategoryChips() {
        val b = _binding ?: return
        val d = resources.displayMetrics.density
        b.categoryRow.removeAllViews()
        categories.forEach { (labelRes, query) ->
            val chip = android.widget.TextView(requireContext()).apply {
                text = getString(labelRes)
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_chip)
                isSelected = category == query
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (category == query) R.color.white else R.color.on_surface))
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                setOnClickListener {
                    if (category == query) return@setOnClickListener
                    com.play.tube.util.Haptics.soft(it)
                    category = query
                    bindCategoryChips()
                    b.list.scrollToPosition(0)
                    load()
                }
            }
            b.categoryRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * d).toInt() })
        }
    }

    fun scrollToTop() {
        _binding?.list?.smoothScrollToPosition(0)
    }

    /** Connection dropped while browsing: replace the stale feed with skeletons. */
    fun onWentOffline() {
        val b = _binding ?: return
        adapter.submitList(emptyList())
        visibleCountry = null
        b.swipe.isRefreshing = false
        b.errorView.isVisible = false
        showSkeleton(b.list, SkeletonAdapter.STYLE_VIDEO, count = 10)
        lastLoadedAt = 0L
    }

    fun onBackOnline() {
        if (_binding != null && (adapter.currentList.isEmpty() || stale())) load()
    }

    private fun isCurrent(b: FragmentHomeBinding, country: String, generation: Long): Boolean =
        _binding === b && isAdded && generation == feedGeneration && AppPrefs.countryOrDefault == country

    override fun onDestroyView() {
        feedGeneration++
        loadJob?.cancel()
        loadedCountry = null
        visibleCountry = null
        lastLoadedAt = 0L
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** The feed re-fetches itself at most once every 5 minutes. */
        const val AUTO_REFRESH_MS = 5 * 60 * 1000L
    }
}
