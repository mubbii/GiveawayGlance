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
 * NOTE: this hits Reddit's public read-only JSON endpoint (a www.reddit.com URL ending in .json),
 * no login needed. Reddit has tightened rate limits / bot detection on this
 * endpoint over time — if you start getting empty results or 403s, the fix is
 * to switch this to proper OAuth (script app, same credential flow PRAW uses
 * on desktop) rather than the anonymous JSON endpoint. Keeping it anonymous
 * first because it's the simplest thing that could work.
 */
class RedditRepository(private val client: OkHttpClient) {

    companion object {
        const val SOURCE_KEY = "reddit_gog_weekly"
        private const val SOURCE_NAME = "r/gog Weekly Giveaway"
        private const val SEARCH_URL =
            "https://www.reddit.com/r/gog/search.json" +
                "?q=title:%22Weekly%20Code%20Giveaway%22" +
                "&restrict_sr=on&sort=new&limit=5"
        // Reddit blocks requests with generic/blank User-Agents.
        private const val USER_AGENT = "android:com.mubbii.glance:v1.0 (by /u/mubbii)"
    }

    fun fetchLatest(): GiveawayItem? {
        val request = Request.Builder()
            .url(SEARCH_URL)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            val children = JSONObject(body)
                .getJSONObject("data")
                .getJSONArray("children")
            if (children.length() == 0) return null

            // children are already sorted "new" by the query; take the first.
            val post = children.getJSONObject(0).getJSONObject("data")
            val id = post.getString("id")
            val title = post.getString("title")
            val author = post.optString("author", "unknown")
            val permalink = post.getString("permalink")

            return GiveawayItem(
                sourceName = SOURCE_NAME,
                id = id,
                title = title,
                snippet = "Posted by u/$author",
                url = "https://www.reddit.com$permalink"
            )
        }
    }
}
