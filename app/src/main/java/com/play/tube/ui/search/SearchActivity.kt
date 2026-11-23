package com.play.tube.ui.search

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.play.tube.R
import com.play.tube.data.ChannelEntry
import com.play.tube.data.LocalStore
import com.play.tube.data.RecommendEngine
import com.play.tube.data.YtRepository
import com.play.tube.databinding.ActivitySearchBinding
import com.play.tube.playback.PlaybackCenter
import com.play.tube.ui.channel.ChannelActivity
import com.play.tube.ui.common.ChannelRowAdapter
import com.play.tube.ui.common.MusicRowAdapter
import com.play.tube.ui.common.SkeletonAdapter
import com.play.tube.ui.common.SuggestionAdapter
import com.play.tube.ui.common.SuggestionRow
import com.play.tube.ui.common.VideoAdapter
import com.play.tube.ui.common.VideoUiModel
import com.play.tube.ui.common.showSkeleton
import com.play.tube.ui.common.toQueueEntry
import com.play.tube.ui.common.toUiModel
import com.play.tube.ui.music.NowPlayingActivity
import com.play.tube.ui.player.PlayerActivity
import com.play.tube.util.Formatters
import com.play.tube.util.Themes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/**
 * Search screen for both videos and music. While the user types, keyword
 * suggestions from YouTube are shown; picking one runs the search.
 *
 * Video search has two result tabs — Videos and Channels — plus
 * client-side upload-date filters (today / this week / this month) applied
 * to the parsed "N days ago" labels of each result. The filters live behind
 * a dedicated funnel button in the search bar (tinted to the accent colour
 * while a filter is active) instead of a permanent row of chips. While
 * results load, skeleton rows stand in for the list instead of a spinner.
 */
class SearchActivity : AppCompatActivity() {

    /** Result tab shown on the video search screen. */
    private enum class SearchTab { VIDEOS, CHANNELS }

    private lateinit var binding: ActivitySearchBinding
    private var musicMode: Boolean = false
    private var prefillQuery: String? = null

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var channelAdapter: ChannelRowAdapter
    private lateinit var musicAdapter: MusicRowAdapter
    private lateinit var suggestionAdapter: SuggestionAdapter

    private val items = mutableListOf<VideoUiModel>()
    private val channelItems = mutableListOf<ChannelEntry>()
    private var page: Page? = null
    private var query: String = ""
    private var isLoading = false
    private var searchInFlight = false
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var lastSuggestions: List<String> = emptyList()

    private var tab: SearchTab = SearchTab.VIDEOS

    /** Active upload-date filter for the Videos tab (null = any time). */
    private var dateWindow: Formatters.DateWindow? = null

    /** Duration bucket filter (client-side) and result ordering. */
    private enum class DurationFilter { ANY, SHORT, MEDIUM, LONG }
    private enum class SortMode { RELEVANCE, NEWEST, VIEWS }
    private var durationFilter = DurationFilter.ANY
    private var sortMode = SortMode.RELEVANCE

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) {
            binding.searchInput.setText(spoken)
            binding.searchInput.setSelection(spoken.length)
            doSearch()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.play.tube.ui.common.MiniBar.attach(this)

        musicMode = intent.getBooleanExtra(EXTRA_MUSIC, false)
        prefillQuery = intent.getStringExtra(EXTRA_PREFILL)

        if (musicMode) {
            binding.searchInput.hint = getString(R.string.search_music_hint)
        } else {
            // Tabs + the filter button are a video-search feature only.
            // Videos only: the Channels tab is gone, so the row stays hidden.
            binding.tabRow.isVisible = false
            binding.tabVideos.isSelected = true
            binding.filterButton.isVisible = true

            binding.tabVideos.setOnClickListener { switchTab(SearchTab.VIDEOS) }
            binding.tabChannels.setOnClickListener { switchTab(SearchTab.CHANNELS) }
            binding.filterButton.setOnClickListener { showFilterPicker() }
        }

        videoAdapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(this, model)
        })
        channelAdapter = ChannelRowAdapter(
            onClick = { channel ->
                ChannelActivity.start(this, channel.url, channel.name)
            }
        )
        musicAdapter = MusicRowAdapter(onClick = { model ->
            playMusic(model)
        })
        suggestionAdapter = SuggestionAdapter(
            onClick = { suggestion ->
                binding.searchInput.setText(suggestion)
                binding.searchInput.setSelection(suggestion.length)
                doSearch()
            },
            onDelete = { entry ->
                LocalStore.removeSearch(this, entry, musicMode)
                val text = binding.searchInput.text?.toString().orEmpty()
                if (text.length < 2) {
                    showHistoryRows()
                } else {
                    showMergedRows(text, lastSuggestions)
                }
            },
            onFill = { suggestion ->
                // Arrow: copy the suggestion into the box to keep editing it.
                binding.searchInput.setText(suggestion)
                binding.searchInput.setSelection(suggestion.length)
                binding.searchInput.requestFocus()
                showKeyboard()
            }
        )

        binding.list.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, com.play.tube.util.Layouts.videoSpan(this))
        binding.list.adapter = if (musicMode) musicAdapter else videoAdapter
        binding.suggestionList.layoutManager = LinearLayoutManager(this)
        binding.suggestionList.adapter = suggestionAdapter

        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= itemCount() - 4 && page != null && !isLoading) {
                    loadMore()
                }
            }
        })

        binding.backButton.setOnClickListener { finish() }
        binding.voiceButton.setOnClickListener { startVoiceSearch() }
        binding.clearButton.setOnClickListener {
            binding.searchInput.setText("")
            showHistoryRows()
            binding.searchInput.requestFocus()
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString().orEmpty()
                binding.clearButton.isVisible = text.isNotEmpty()
                binding.voiceButton.isVisible = text.isEmpty()
                if (text == query) {
                    // The input matches what is already searched: show results.
                    return
                }
                if (text.length < 2) {
                    suggestionJob?.cancel()
                    lastSuggestions = emptyList()
                    showHistoryRows()
                    return
                }
                queueSuggestions(text)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.searchInput.requestFocus()
        showKeyboard()
        // Fresh open with an empty input: surface the recent searches.
        if (prefillQuery.isNullOrBlank()) {
            showHistoryRows()
        }

        prefillQuery?.takeIf { it.isNotBlank() }?.let { prefill ->
            binding.searchInput.setText(prefill)
            binding.searchInput.setSelection(prefill.length)
            doSearch()
        }
    }

    override fun onResume() {
        super.onResume()
        // A theme / accent change while we were in the background.
        Themes.recreateIfNeeded(this)
    }

    // ----- Tabs + date filter -----

    private fun switchTab(newTab: SearchTab) {
        if (newTab == tab) return
        tab = newTab
        binding.tabVideos.isSelected = tab == SearchTab.VIDEOS
        binding.tabChannels.isSelected = tab == SearchTab.CHANNELS
        // Upload-date filters only make sense for videos.
        binding.filterButton.isVisible = tab == SearchTab.VIDEOS
        // Re-run the search for the new tab when there is a query; a
        // half-finished old-tab search is cancelled by doSearch's new job.
        if (query.isNotBlank()) {
            searchJob?.cancel()
            doSearch()
        } else {
            applyListAdapter()
        }
    }

    /**
     * The dedicated Filters button: one bottom sheet with three chip groups
     * (upload date, duration, sort order). Nothing is applied until "Apply".
     */
    private fun showFilterPicker() {
        var pickDate = dateWindow
        var pickDuration = durationFilter
        var pickSort = sortMode

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        fun <T> group(
            title: String,
            options: List<Pair<T, String>>,
            current: () -> T,
            onPick: (T) -> Unit
        ) {
            content.addView(android.widget.TextView(this).apply {
                text = title
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.on_surface_variant))
            }, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(14) })

            val scroller = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
            }
            val flow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            scroller.addView(flow)
            val chips = mutableListOf<Pair<T, android.widget.TextView>>()
            fun refresh() = chips.forEach { (value, tv) -> tv.isSelected = value == current() }
            options.forEach { (value, label) ->
                val tv = android.widget.TextView(this).apply {
                    text = label
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.on_surface))
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { onPick(value); refresh() }
                }
                chips += value to tv
                flow.addView(tv, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, dp(8), dp(8), 0) })
            }
            refresh()
            content.addView(scroller)
        }

        group(
            getString(R.string.filter_group_date),
            listOf<Pair<Formatters.DateWindow?, String>>(
                null to getString(R.string.filter_any),
                Formatters.DateWindow.TODAY to getString(R.string.filter_today),
                Formatters.DateWindow.THIS_WEEK to getString(R.string.filter_this_week),
                Formatters.DateWindow.THIS_MONTH to getString(R.string.filter_this_month)
            ),
            { pickDate }, { pickDate = it }
        )
        group(
            getString(R.string.filter_group_duration),
            listOf(
                DurationFilter.ANY to getString(R.string.filter_any),
                DurationFilter.SHORT to getString(R.string.filter_dur_short),
                DurationFilter.MEDIUM to getString(R.string.filter_dur_medium),
                DurationFilter.LONG to getString(R.string.filter_dur_long)
            ),
            { pickDuration }, { pickDuration = it }
        )
        group(
            getString(R.string.filter_group_sort),
            listOf(
                SortMode.RELEVANCE to getString(R.string.sort_relevance),
                SortMode.NEWEST to getString(R.string.sort_newest),
                SortMode.VIEWS to getString(R.string.sort_views)
            ),
            { pickSort }, { pickSort = it }
        )

        com.play.tube.ui.common.PremiumSheet(this)
            .icon(R.drawable.ic_filter)
            .title(getString(R.string.search_filters))
            .view(content)
            .primary(getString(R.string.filter_apply)) {
                applyFilters(pickDate, pickDuration, pickSort)
                true
            }
            .secondary(getString(R.string.filter_reset)) {
                applyFilters(null, DurationFilter.ANY, SortMode.RELEVANCE)
            }
            .show()
    }

    private fun applyFilters(
        window: Formatters.DateWindow?,
        duration: DurationFilter,
        sort: SortMode
    ) {
        if (dateWindow == window && durationFilter == duration && sortMode == sort) return
        dateWindow = window
        durationFilter = duration
        sortMode = sort
        // Accent tint while any filter is active, muted otherwise.
        val active = window != null || duration != DurationFilter.ANY || sort != SortMode.RELEVANCE
        binding.filterButton.setColorFilter(
            if (active) Themes.accentColor(this) else getColor(R.color.on_surface_variant)
        )
        if (query.isNotBlank()) {
            searchJob?.cancel()
            doSearch()
        }
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Points the results list at the adapter of the active tab. */
    private fun applyListAdapter() {
        binding.list.adapter = when {
            musicMode -> musicAdapter
            tab == SearchTab.CHANNELS -> channelAdapter
            else -> videoAdapter
        }
    }

    // ----- Search -----

    private fun playMusic(model: VideoUiModel) {
        PlaybackCenter.playMusic(model.toQueueEntry(isMusic = true), radio = true)
        NowPlayingActivity.start(this)
    }

    private fun itemCount(): Int = when {
        musicMode -> musicAdapter.itemCount
        tab == SearchTab.CHANNELS -> channelAdapter.itemCount
        else -> videoAdapter.itemCount
    }

    private fun queueSuggestions(text: String) {
        suggestionJob?.cancel()
        suggestionJob = lifecycleScope.launch {
            delay(250)
            try {
                val suggestions = YtRepository.suggestions(text)
                if (binding.searchInput.text?.toString() == text) {
                    lastSuggestions = suggestions
                    showMergedRows(text, suggestions)
                }
            } catch (e: Exception) {
                // Suggestions are best-effort only.
            }
        }
    }

    /** Empty input: show the saved search history (newest first). */
    private fun showHistoryRows() {
        val searches = LocalStore.searches(this, musicMode).take(15)
        if (searches.isEmpty()) {
            suggestionAdapter.submitList(emptyList())
            binding.suggestionList.isVisible = false
            return
        }
        suggestionAdapter.submitList(searches.map { SuggestionRow(it, isHistory = true) })
        binding.suggestionList.isVisible = true
    }

    /** Typing: matching saved searches on top, remote suggestions below. */
    private fun showMergedRows(text: String, suggestions: List<String>) {
        val lower = text.lowercase()
        val historyMatches = LocalStore.searches(this, musicMode)
            .filter { it.lowercase().contains(lower) }
            .take(5)
        val historyKeys = historyMatches.map { it.lowercase() }.toSet()
        val rows = historyMatches.map { SuggestionRow(it, isHistory = true) } +
            suggestions
                .filter { it.lowercase() !in historyKeys }
                .take(10)
                .map { SuggestionRow(it, isHistory = false) }

        val showResults = itemCount() > 0 || searchInFlight || binding.errorView.isVisible
        if (rows.isEmpty() || (showResults && rows.firstOrNull()?.text == query)) {
            binding.suggestionList.isVisible = false
        } else {
            binding.suggestionList.isVisible = true
        }
        suggestionAdapter.submitList(rows)
    }

    private fun doSearch() {
        val text = binding.searchInput.text?.toString()?.trim().orEmpty()
        hideKeyboard()
        if (text.isEmpty()) return
        query = text
        page = null
        items.clear()
        channelItems.clear()
        RecommendEngine.logSearch(this, text, musicMode)
        binding.suggestionList.isVisible = false
        videoAdapter.submitList(emptyList())
        channelAdapter.submitList(emptyList())
        musicAdapter.submitList(emptyList())
        binding.errorView.isVisible = false
        binding.emptyView.isVisible = false
        searchInFlight = true

        // Skeleton rows stand in for the incoming results (video cards for
        // the Videos tab, compact rows for channels / music).
        val style = if (!musicMode && tab == SearchTab.VIDEOS) {
            SkeletonAdapter.STYLE_VIDEO
        } else {
            SkeletonAdapter.STYLE_ROW
        }
        showSkeleton(binding.list, style, count = 8)

        searchJob = lifecycleScope.launch {
            val self = coroutineContext[Job]
            try {
                when {
                    musicMode -> {
                        val result = YtRepository.searchMusic(query, null)
                        items.addAll(result.items.map { it.toUiModel() })
                        page = result.nextPage
                        applyListAdapter()
                        musicAdapter.submitList(items.toList())
                        finishSearch(items.isNotEmpty(), musicEmpty)
                    }
                    tab == SearchTab.CHANNELS -> {
                        val result = YtRepository.searchChannels(query, null)
                        channelItems.addAll(result.items)
                        page = result.nextPage
                        applyListAdapter()
                        channelAdapter.submitList(channelItems.toList())
                        finishSearch(channelItems.isNotEmpty(), channelsEmpty)
                    }
                    else -> {
                        val result = YtRepository.searchVideos(query, null)
                        // Upload-date filter is applied client-side: NewPipe's
                        // YouTube search has no server-side date parameter,
                        // but every result carries a "N days ago" label we
                        // can parse.
                        val filtered = result.items.map { it.toUiModel() }
                            .filter { passesDateFilter(it) }
                        items.addAll(filtered)
                        page = result.nextPage
                        applyListAdapter()
                        videoAdapter.submitList(sortedItems(items))
                        finishSearch(items.isNotEmpty(), noResultsEmpty)
                    }
                }
            } catch (e: CancellationException) {
                // Switching tab / filter cancels the in-flight search: not
                // an error, the replacement search is already running.
                throw e
            } catch (e: Exception) {
                // Drop the skeleton behind the error message.
                applyListAdapter()
                binding.errorText.text = Formatters.friendlyException(e)
                binding.errorView.isVisible = true
            } finally {
                if (searchJob === self) searchInFlight = false
            }
        }
    }

    private val musicEmpty: String get() = getString(R.string.error_no_songs)
    private val channelsEmpty: String get() = getString(R.string.error_no_channels)
    private val noResultsEmpty: String get() = getString(R.string.error_no_results)

    private fun finishSearch(anyResults: Boolean, emptyMessage: String) {
        if (!anyResults) {
            binding.emptyView.isVisible = true
            binding.errorText.text = emptyMessage
            binding.errorView.isVisible = true
        }
    }

    /** Client-side upload-date check for one video result. */
    private fun passesDateFilter(model: VideoUiModel): Boolean {
        val window = dateWindow
        if (window != null && !Formatters.withinDateWindow(model.uploadDate, window)) return false
        val min = model.durationSec / 60.0
        return when (durationFilter) {
            DurationFilter.ANY -> true
            DurationFilter.SHORT -> model.durationSec > 0 && min < 4.0
            DurationFilter.MEDIUM -> min >= 4.0 && min <= 20.0
            DurationFilter.LONG -> min > 20.0
        }
    }

    /** Orders the loaded results for the chosen sort mode (stable for relevance). */
    private fun sortedItems(list: List<VideoUiModel>): List<VideoUiModel> = when (sortMode) {
        SortMode.RELEVANCE -> list
        SortMode.VIEWS -> list.sortedByDescending { it.viewCount }
        SortMode.NEWEST -> list.sortedBy { Formatters.parseAgeDays(it.uploadDate) ?: Double.MAX_VALUE }
    }

    private fun loadMore() {
        val nextPage = page ?: return
        isLoading = true
        lifecycleScope.launch {
            try {
                when {
                    musicMode -> {
                        val result = YtRepository.searchMusic(query, nextPage)
                        val known = items.map { it.url }.toSet()
                        items.addAll(result.items.map { it.toUiModel() }.filter { it.url !in known })
                        page = result.nextPage
                        musicAdapter.submitList(items.toList())
                    }
                    tab == SearchTab.CHANNELS -> {
                        val result = YtRepository.searchChannels(query, nextPage)
                        val known = channelItems.map { it.url }.toSet()
                        channelItems.addAll(result.items.filter { it.url !in known })
                        page = result.nextPage
                        channelAdapter.submitList(channelItems.toList())
                    }
                    else -> {
                        val result = YtRepository.searchVideos(query, nextPage)
                        val known = items.map { it.url }.toSet()
                        items.addAll(
                            result.items.map { it.toUiModel() }
                                .filter { it.url !in known && passesDateFilter(it) }
                        )
                        page = result.nextPage
                        videoAdapter.submitList(sortedItems(items))
                    }
                }
            } catch (e: Exception) {
                page = null
            } finally {
                isLoading = false
            }
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    companion object {
        private const val EXTRA_MUSIC = "extra_music"
        private const val EXTRA_PREFILL = "extra_prefill"

        fun intent(context: Context, music: Boolean, prefill: String? = null): Intent =
            Intent(context, SearchActivity::class.java).apply {
                putExtra(EXTRA_MUSIC, music)
                putExtra(EXTRA_PREFILL, prefill)
            }
    }
}
