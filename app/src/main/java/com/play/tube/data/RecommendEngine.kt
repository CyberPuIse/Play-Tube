package com.play.tube.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.ln
import kotlin.math.pow

/**
 * Lightweight on-device recommendation engine.
 *
 * It quietly collects usage signals — played videos and search queries — and
 * combines them with the local watch history, favorites and subscriptions
 * (all already stored by [LocalStore]) into two affinity profiles:
 *
 *  * keyword weights: what the user's content "is about" (title words,
 *    search words, subscription names, with recency decay)
 *  * channel weights: which uploaders the user prefers
 *
 * Nothing ever leaves the device. The home feed uses [snapshot] +
 * [score] to re-rank the region's trending blend and to mix in
 * "because you watched …" candidates taken from the related lists of
 * recently watched videos.
 */
object RecommendEngine {

    private const val PREFS = "playtube_reco"
    private const val KEY_PLAYS = "plays_json"
    private const val KEY_GEN = "generation"

    private const val MAX_PLAYS = 120

    /** Half-life (days) of a play signal: older plays count progressively less. */
    private const val PLAY_HALF_LIFE_DAYS = 14.0

    /** How many recently watched videos feed the "because you watched" zone. */
    private const val RELATED_SOURCE_COUNT = 8

    /** One logged play of a video (count = how many times it was played). */
    private data class Play(
        val url: String,
        val title: String,
        val uploader: String,
        val ts: Long,
        val count: Int,
        /** Played as a song (music mode). Songs must not take over the video feed. */
        val music: Boolean = false
    )

    /**
     * Everything the ranking needs, computed once per feed load.
     */
    data class Snapshot(
        val keywordWeights: Map<String, Double>,
        val channelWeights: Map<String, Double>,
        /** Recently played / watched video urls (newest first). */
        val recentWatchedUrls: List<String>,
        /** Videos whose related lists feed the personalized section (max [RELATED_SOURCE_COUNT]). */
        val relatedSources: List<String>,
        val hasSignal: Boolean,
        /** Title word sets of the last few things played: used to avoid "the same thing again". */
        val recentTitleTokens: List<Set<String>> = emptyList(),
        /** Videos the user marked "Not interested". */
        val blockedUrls: Set<String> = emptySet(),
        /** Uploaders the user pushed down (lower-cased name -> times hidden). */
        val channelPenalty: Map<String, Int> = emptyMap()
    )

    // ----- Signal collection -----

    /** Called by PlaybackCenter every time a video or song actually starts. */
    fun logPlay(context: Context, url: String, title: String, uploader: String, music: Boolean = false) {
        if (url.isBlank() || url.startsWith("file://")) return
        val ctx = context.applicationContext
        val plays = readPlays(ctx).toMutableList()
        val existing = plays.indexOfFirst { it.url == url }
        val updated = if (existing >= 0) {
            val old = plays.removeAt(existing)
            old.copy(ts = now(), count = old.count + 1, music = old.music || music)
        } else {
            Play(url, title, uploader, now(), 1, music)
        }
        plays.add(0, updated)
        while (plays.size > MAX_PLAYS) plays.removeAt(plays.size - 1)
        writePlays(ctx, plays)
        bump(ctx)
    }

    /**
     * Called by SearchActivity for every executed search. The query itself is
     * stored by [LocalStore] as the user-facing search history; this only
     * bumps the profile generation so the home feed refreshes.
     */
    fun logSearch(context: Context, query: String, music: Boolean = false) {
        val q = query.trim()
        if (q.isEmpty()) return
        LocalStore.addSearch(context, q, music)
        bump(context)
    }

    /** Wipes play signals (called when the user clears the watch history). */
    fun clearPlays(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit().remove(KEY_PLAYS).apply()
        bump(ctx)
    }

    /** Wipes search signals (called when the user clears the search history). */
    fun clearSearches(context: Context) {
        LocalStore.clearSearches(context)
        bump(context)
    }

    /** Cheap change counter so Home knows when the profile changed. */
    fun generation(context: Context): Int = prefs(context).getInt(KEY_GEN, 0)

    /** Public bump for restores (import backup) so the home feed refreshes. */
    fun bumpGeneration(context: Context) {
        bump(context)
    }

    private fun bump(context: Context) {
        prefs(context).edit().putInt(KEY_GEN, generation(context) + 1).apply()
    }

    // ----- Profile -----

    fun snapshot(context: Context): Snapshot {
        val ctx = context.applicationContext
        val keywords = HashMap<String, Double>()
        val channels = HashMap<String, Double>()

        fun boostKeywords(text: String?, weight: Double) {
            if (text.isNullOrBlank()) return
            tokenize(text).forEach { keywords[it] = (keywords[it] ?: 0.0) + weight }
        }

        fun boostChannel(uploader: String?, weight: Double) {
            val key = uploader?.trim()?.lowercase()
            if (key.isNullOrEmpty()) return
            channels[key] = (channels[key] ?: 0.0) + weight
        }

        // Searches: the strongest statement of intent, newest first with decay.
        LocalStore.searches(ctx).take(12).forEachIndexed { index, search ->
            tokenize(search).forEach {
                keywords[it] = (keywords[it] ?: 0.0) + 3.0 * decay(index)
            }
        }

        // Favorites: titles + uploaders the user explicitly saved.
        LocalStore.favorites(ctx).forEach { fav ->
            boostKeywords(fav.title, 2.5)
            boostChannel(fav.uploader, 2.0)
        }

        // Downloads: a saved file is the strongest possible interest vote.
        LocalStore.downloads(ctx).filter { it.status == com.play.tube.download.DownloadCenter.STATUS_DONE }
            .forEach { record ->
                boostKeywords(record.title, 2.8)
                boostChannel(record.uploader, 3.0)
            }

        // Subscriptions: strongest channel signal.
        LocalStore.subscriptions(ctx).forEach { sub ->
            boostChannel(sub.name, 4.0)
            boostKeywords(sub.name, 1.5)
        }

        // Play log: counts, and time decay so the profile follows the user's
        // current taste instead of everything they ever opened.
        val nowMs = now()
        val recentUrls = LinkedHashSet<String>()
        val videoSources = LinkedHashSet<String>()
        val musicSources = LinkedHashSet<String>()
        val recentTitles = ArrayList<Set<String>>()
        readPlays(ctx).forEachIndexed { index, play ->
            if (index < 12) recentTitles.add(tokenize(play.title).toSet())
            val ageDays = ((nowMs - play.ts).coerceAtLeast(0L)) / 86_400_000.0
            val freshness = 0.5.pow(ageDays / PLAY_HALF_LIFE_DAYS)
            val positional = decay(index)
            // Songs count far less: someone who listens to music all day still
            // wants a varied video feed, not only more of the same songs.
            val kind = if (play.music) 0.3 else 1.0
            val w = (1.2 + 0.3 * ln(1.0 + play.count)) * freshness * positional * kind
            boostKeywords(play.title, w)
            boostChannel(play.uploader, 1.5 * freshness * kind)
            if (play.url.startsWith("http")) {
                recentUrls.add(play.url)
                if (play.music) musicSources.add(play.url) else videoSources.add(play.url)
            }
        }

        // Music history: songs the user actually listened to.
        LocalStore.musicHistory(ctx).take(40).forEachIndexed { index, song ->
            boostKeywords(song.title, 0.35 * decay(index))
            boostChannel(song.uploader, 0.5 * decay(index))
            if (song.url.startsWith("http")) {
                recentUrls.add(song.url)
                musicSources.add(song.url)
            }
        }

        // Watch history: a weaker, broader echo of the play log.
        LocalStore.history(ctx).forEach { entry ->
            boostKeywords(entry.title, 1.0)
            boostChannel(entry.uploader, 1.0)
            if (entry.url.startsWith("http")) {
                recentUrls.add(entry.url)
                videoSources.add(entry.url)
            }
        }

        val recent = recentUrls.toList()
        // "Because you watched" sources: mostly real videos, at most 2 songs.
        val sources = (videoSources.take(RELATED_SOURCE_COUNT - 2) +
            musicSources.filter { it !in videoSources }.take(2)).distinct().take(RELATED_SOURCE_COUNT)
        return Snapshot(
            keywordWeights = keywords,
            channelWeights = channels,
            recentWatchedUrls = recent,
            relatedSources = sources.ifEmpty { recent.take(RELATED_SOURCE_COUNT) },
            recentTitleTokens = recentTitles,
            hasSignal = keywords.isNotEmpty() || channels.isNotEmpty(),
            blockedUrls = readBlocked(ctx).first,
            channelPenalty = readBlocked(ctx).second
        )
    }

    /** How well a candidate matches the profile (higher = better). */
    fun score(item: StreamInfoItem, snap: Snapshot, relatedBonus: Double = 0.0): Double {
        var score = 0.0
        val title = item.name.orEmpty()
        var titleTokens = 0
        if (title.isNotBlank()) {
            tokenize(title).forEach { token ->
                titleTokens++
                score += snap.keywordWeights[token] ?: 0.0
            }
        }
        if (item.url in snap.blockedUrls) score -= 100.0
        item.uploaderName?.trim()?.lowercase()?.let { channel ->
            snap.channelPenalty[channel]?.let { score -= 2.5 * it }
            snap.channelWeights[channel]?.let { channelWeight ->
                // Channel affinity is the most reliable signal: give it extra
                // weight relative to loose keyword hits.
                score += channelWeight * 1.4
            }
        }
        // Long titles accumulate keyword hits by pure volume; normalize a
        // little so a 20-word clickbait title does not outrank a real match.
        if (titleTokens > 8) {
            score /= (titleTokens / 8.0)
        }
        // Novelty: a candidate that is basically a recently played title again
        // (same song / same series) is demoted so the feed shows *related*,
        // not *identical*, things.
        if (snap.recentTitleTokens.isNotEmpty() && title.isNotBlank()) {
            val tokens = tokenize(title).toSet()
            if (tokens.size >= 2 && snap.recentTitleTokens.any { overlap(tokens, it) >= 0.6 }) score -= 3.5
        }
        // "Because you watched": candidates that came from a recent video's
        // related list start with a head start (newest source = biggest).
        score += relatedBonus
        score += freshnessBonus(item.textualUploadDate)
        score += popularityBonus(item.viewCount)
        // Clips have their own Shorts tab; keep them out of the main feed.
        if (item.duration in 1..SHORT_MAX_SECONDS) score -= 3.0
        return score
    }

    private const val SHORT_MAX_SECONDS = 75L

    /** Share of the smaller word set that also appears in the other (0..1). */
    fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / minOf(a.size, b.size)
    }

    /** Public so the feed builder can suppress near-duplicate titles. */
    fun titleTokens(title: String?): Set<String> = if (title.isNullOrBlank()) emptySet() else tokenize(title).toSet()

    /** Newer uploads win ties and get a gentle push; very old ones a gentle pull. */
    private fun freshnessBonus(textual: String?): Double {
        val s = textual?.lowercase()?.trim().orEmpty()
        if (s.isEmpty()) return 0.0
        val n = Regex("(\\d+)").find(s)?.value?.toIntOrNull() ?: 1
        return when {
            "second" in s || "minute" in s || "hour" in s -> 1.0
            "day" in s -> if (n <= 1) 1.0 else if (n <= 6) 0.6 else 0.4
            "week" in s -> if (n <= 2) 0.4 else 0.25
            "month" in s -> if (n <= 1) 0.3 else 0.0
            "year" in s -> -0.3
            else -> 0.0
        }
    }

    /** Small, capped popularity prior so well-liked videos break ties. */
    private fun popularityBonus(views: Long): Double =
        if (views <= 0L) 0.0 else minOf(1.5, 0.15 * ln(1.0 + views / 1000.0))

    // ----- "Not interested" -----

    private const val KEY_BLOCKED = "blocked_json"

    fun markNotInterested(context: Context, url: String, uploader: String) =
        editBlocked(context) { urls, channels ->
            urls.remove(url); urls.add(0, url)
            while (urls.size > 400) urls.removeAt(urls.size - 1)
            val key = uploader.trim().lowercase()
            if (key.isNotEmpty()) channels[key] = (channels[key] ?: 0) + 1
        }

    fun undoNotInterested(context: Context, url: String, uploader: String) =
        editBlocked(context) { urls, channels ->
            urls.remove(url)
            val key = uploader.trim().lowercase()
            val n = (channels[key] ?: 0) - 1
            if (n > 0) channels[key] = n else channels.remove(key)
        }

    private fun editBlocked(
        context: Context,
        change: (MutableList<String>, MutableMap<String, Int>) -> Unit
    ) {
        val ctx = context.applicationContext
        val (u, c) = readBlocked(ctx)
        val urls = u.toMutableList()
        val channels = c.toMutableMap()
        change(urls, channels)
        val obj = JSONObject()
            .put("urls", JSONArray(urls))
            .put("channels", JSONObject(channels as Map<*, *>))
        prefs(ctx).edit().putString(KEY_BLOCKED, obj.toString()).apply()
        bump(ctx)
    }

    private fun readBlocked(context: Context): Pair<Set<String>, Map<String, Int>> {
        val raw = prefs(context).getString(KEY_BLOCKED, null) ?: return emptySet<String>() to emptyMap()
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("urls") ?: JSONArray()
            val urls = (0 until arr.length()).map { arr.getString(it) }.toSet()
            val ch = o.optJSONObject("channels")
            val map = HashMap<String, Int>()
            ch?.keys()?.forEach { k -> map[k] = ch.optInt(k, 1) }
            urls to map
        } catch (e: Exception) {
            emptySet<String>() to emptyMap()
        }
    }

    // ----- Internals -----

    private fun now(): Long = System.currentTimeMillis()

    private fun decay(index: Int): Double = 0.92.pow(index)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Lower-cased words (any script) of length >= 3, without noise words. */
    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.length >= 3 && it !in STOPWORDS }

    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "you", "your", "from", "this", "that",
        "video", "videos", "official", "full", "hd", "new", "best", "top",
        "song", "songs", "music", "audio", "lyrics", "live", "part", "feat",
        "ft", "official", "video", "mv", "clip", "shorts", "trailer", "how",
        "what", "why", "when", "who", "are", "was", "has", "have", "not",
        "but", "can", "will", "one", "two", "all", "out", "get", "let",
        "episode", "ep", "season", "vs", "vol", "bonus", "online", "free",
        "watch", "subscribe", "channel", "like", "share"
    )

    private fun readPlays(context: Context): List<Play> {
        val raw = prefs(context).getString(KEY_PLAYS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Play(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    uploader = o.optString("uploader"),
                    ts = o.optLong("ts"),
                    count = o.optInt("count", 1),
                    music = o.optBoolean("m", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writePlays(context: Context, plays: List<Play>) {
        val arr = JSONArray()
        plays.forEach { p ->
            arr.put(
                JSONObject()
                    .put("url", p.url)
                    .put("title", p.title)
                    .put("uploader", p.uploader)
                    .put("ts", p.ts)
                    .put("count", p.count)
                    .put("m", p.music)
            )
        }
        prefs(context).edit().putString(KEY_PLAYS, arr.toString()).apply()
    }
}
