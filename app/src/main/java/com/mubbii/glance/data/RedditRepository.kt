package com.mubbii.glance.data

import com.mubbii.glance.model.GiveawayItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Mirrors the logic in your existing PRAW-based r/gog monitor: search the
 * subreddit for the weekly "Code Giveaway" thread and surface the newest
 * match, same idea as replacing a hardcoded URL with dynamic search.
 *
 * NOTE: this hits Reddit's public read-only JSON endpoint (www.reddit.com/*.json),
 * no login needed. Reddit has tightened rate limits / bot detection on this
 * endpoint over time — if you start getting empty results or 403s, the fix is
 * to switch this to proper OAuth (script app, same credential flow PRAW uses
 * on desktop) rather than the anonymous JSON endpoint. Keeping it anonymous
 * first because it's the simplest thing that could work.
 */
class RedditRepository(private val client: OkHttpClient) : GiveawaySource {

    override val key = "reddit_gog_weekly"
    override val name = "r/gog Weekly Giveaway"

    companion object {
        private const val SEARCH_URL =
            "https://www.reddit.com/r/gog/search.json" +
                "?q=title:%22Weekly%20Code%20Giveaway%22" +
                "&restrict_sr=on&sort=new&limit=5"
        // Reddit blocks requests with generic/blank User-Agents.
        private const val USER_AGENT = "android:com.mubbii.glance:v1.0 (by /u/mubbii)"
    }

    override fun fetchLatest(): List<GiveawayItem> {
        val request = Request.Builder()
            .url(SEARCH_URL)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()

            val children = JSONObject(body)
                .getJSONObject("data")
                .getJSONArray("children")
            if (children.length() == 0) return emptyList()

            // children are already sorted "new" by the query; take the first.
            val post = children.getJSONObject(0).getJSONObject("data")
            val id = post.getString("id")
            val title = post.getString("title")
            val author = post.optString("author", "unknown")
            val permalink = post.getString("permalink")

            return listOf(
                GiveawayItem(
                    sourceName = name,
                    id = id,
                    title = title,
                    snippet = "Posted by u/$author",
                    url = "https://www.reddit.com$permalink"
                )
            )
        }
    }
}
