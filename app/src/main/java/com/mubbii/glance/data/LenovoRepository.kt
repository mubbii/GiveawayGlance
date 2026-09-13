package com.mubbii.glance.data

import com.mubbii.glance.model.GiveawayItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Lenovo's "Game Key Drops" page (gaming.lenovo.com/game-key-drops) is a
 * scheduled-event board, not a forum you post in — the point is knowing
 * WHEN the next drop happens, since Lenovo sometimes shows a date and
 * sometimes just says "Coming Soon" with none yet.
 *
 * IMPORTANT: Lenovo's own `status` field ("Active"/"Coming Soon"/"Expired")
 * does NOT reliably track real-world timing — some drops (e.g. recurring
 * "restocked" currency/item pools) stay marked Active long after their
 * listed date has passed, since that field is set manually on their end,
 * not derived from the date. So this does NOT trust `status` to decide
 * what's current. Instead it fetches every non-Expired-looking post,
 * computes each one's own end-of-relevance instant from its own
 * start_date/end_date fields, and drops anything whose date has already
 * passed — regardless of what Lenovo's status field claims.
 *
 * It also excludes recurring in-game-currency/item drops by title keyword
 * (see EXCLUDED_TITLE_KEYWORDS) since those aren't the single-game key
 * giveaways you're after.
 *
 * Reverse-engineered from a live browser session (not guessed): the page
 * runs on the Bettermode community platform, backed by a GraphQL API at
 * api.bettermode.com. Getting data out takes two calls:
 *
 * 1. `Tokens` query with just `networkDomain` — no login needed, hands
 *    back a short-lived anonymous/guest access token.
 * 2. `GetPosts` query, sent with that token as `Authorization: Bearer`,
 *    filtered to this specific post type + space.
 *
 * The space/post-type/status IDs below are Lenovo's real, fixed IDs
 * confirmed from a live captured request.
 */
class LenovoRepository(private val client: OkHttpClient) : GiveawaySource {

    override val key = "lenovo_key_drops"
    override val name = "Lenovo Game Key Drops"

    companion object {
        private const val API_URL = "https://api.bettermode.com/"
        private const val NETWORK_DOMAIN = "gaming-lenovo-prod.bettermode.io"
        private const val SPACE_ID = "y4nnEocBKMA2"
        private const val POST_TYPE_ID = "tgy4OKncNC5kziW"

        // Real option IDs for the "status" custom field on this post type.
        private const val STATUS_ACTIVE = "AmAI_EO502mWht5Fb6OE0"
        private const val STATUS_COMING_SOON = "X7FhO8Z5w0QXFFnoFHVpZ"
        private const val STATUS_EXPIRED = "vFkvw3-q42NRPSbfq26kG"

        // Case-insensitive title keywords to skip — recurring reward pools,
        // not single-game key giveaways. Add more here if new categories
        // like this show up.
        private val EXCLUDED_TITLE_KEYWORDS = listOf("currency")

        private const val TOKENS_QUERY = """
            query Tokens(${'$'}networkDomain: String) {
              tokens(networkDomain: ${'$'}networkDomain) {
                accessToken
              }
            }
        """

        private const val GET_POSTS_QUERY = """
            query GetPosts(${'$'}spaceIds: [ID!], ${'$'}postTypeIds: [String!], ${'$'}limit: Int!, ${'$'}orderByString: String, ${'$'}reverse: Boolean, ${'$'}filterBy: [PostListFilterByInput!]) {
              posts(
                spaceIds: ${'$'}spaceIds
                postTypeIds: ${'$'}postTypeIds
                limit: ${'$'}limit
                orderByString: ${'$'}orderByString
                reverse: ${'$'}reverse
                filterBy: ${'$'}filterBy
              ) {
                nodes {
                  id
                  slug
                  title
                  url
                  fields {
                    key
                    value
                  }
                }
              }
            }
        """
    }

    override fun fetchLatest(): List<GiveawayItem> {
        val accessToken = fetchGuestAccessToken() ?: return emptyList()
        return fetchUpcomingDrops(accessToken)
    }

    private fun fetchGuestAccessToken(): String? {
        val body = JSONObject().apply {
            put("query", TOKENS_QUERY)
            put("variables", JSONObject().put("networkDomain", NETWORK_DOMAIN))
            put("operationName", "Tokens")
        }

        val response = post(body) ?: return null
        return response
            .optJSONObject("data")
            ?.optJSONObject("tokens")
            ?.optString("accessToken")
            ?.takeIf { it.isNotBlank() }
    }

    private fun fetchUpcomingDrops(accessToken: String): List<GiveawayItem> {
        // Ask for everything that isn't explicitly Expired — we still do
        // our own date filtering below rather than trusting this alone.
        val filterBy = JSONArray().put(
            JSONObject().apply {
                put("keyString", "fields.status")
                put("operator", "in")
                put("value", "[\"$STATUS_ACTIVE\",\"$STATUS_COMING_SOON\"]")
            }
        )
        val variables = JSONObject().apply {
            put("spaceIds", JSONArray().put(SPACE_ID))
            put("postTypeIds", JSONArray().put(POST_TYPE_ID))
            put("limit", 20)
            put("orderByString", "fields.start_date")
            put("reverse", false)
            put("filterBy", filterBy)
        }
        val body = JSONObject().apply {
            put("query", GET_POSTS_QUERY)
            put("variables", variables)
            put("operationName", "GetPosts")
        }

        val response = post(body, accessToken) ?: return emptyList()
        val nodes = response
            .optJSONObject("data")
            ?.optJSONObject("posts")
            ?.optJSONArray("nodes")
            ?: return emptyList()

        val now = Instant.now()
        val items = mutableListOf<GiveawayItem>()

        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val title = node.optString("title", "Key drop")

            if (EXCLUDED_TITLE_KEYWORDS.any { title.contains(it, ignoreCase = true) }) {
                continue
            }

            val fields = fieldsMap(node.optJSONArray("fields"))
            val statusId = fields["status"]
            val startInstant = fields["start_date"]?.let { parseInstant(it) }
            val endInstant = fields["end_date"]?.let { parseInstant(it) }

            // The relevant cutoff is end_date if Lenovo set one, otherwise
            // start_date. If that cutoff is already in the past, skip it —
            // regardless of what `status` claims.
            val relevanceCutoff = endInstant ?: startInstant
            if (relevanceCutoff != null && relevanceCutoff.isBefore(now)) {
                continue
            }

            val statusLabel = when (statusId) {
                STATUS_ACTIVE -> "Active"
                STATUS_COMING_SOON -> "Coming Soon"
                STATUS_EXPIRED -> "Expired"
                else -> null
            }

            val postedAt = startInstant?.let { formatDropDate(it) }
            val countdown = startInstant?.let { formatCountdown(now, it) }

            val snippet = buildString {
                append(statusLabel ?: "Key drop")
                if (postedAt != null) {
                    append(" — $postedAt")
                    if (countdown != null) append(" ($countdown)")
                } else {
                    append(" — date not announced yet")
                }
            }

            items.add(
                GiveawayItem(
                    sourceName = name,
                    id = node.optString("id"),
                    title = title,
                    snippet = snippet,
                    url = node.optString("url").ifBlank {
                        "https://gaming.lenovo.com/game-key-drops/post/${node.optString("slug")}"
                    },
                    postedAt = postedAt
                )
            )
        }
        // Only the single soonest upcoming/current drop — that's the "next
        // giveaway" you actually check for, not every technically-future
        // entry. The API query already sorts ascending by start_date, and
        // filtering above preserves that order, so the first surviving
        // item is the soonest one.
        return items.take(1)
    }

    private fun fieldsMap(fields: JSONArray?): Map<String, String> {
        if (fields == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            map[field.optString("key")] = field.optString("value")
        }
        return map
    }

    private fun parseInstant(raw: String): Instant? =
        try {
            // Preferred: a real instant/offset format (e.g. ends in Z).
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            try {
                // What Lenovo actually sends: "2026-09-23T16:00:00" — a
                // local date-time with NO offset at all. Instant.parse()
                // rejects this outright (that was the bug: it silently
                // returned null here, which both blanked the date/countdown
                // AND accidentally let already-past drops slip through,
                // since a null cutoff skipped the "already passed" check
                // entirely). Confirmed against a real screenshot that this
                // value is meant as UTC, not local time.
                java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC)
            } catch (e2: DateTimeParseException) {
                null
            }
        }

    private fun formatDropDate(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }

    /** One-off "time remaining" text computed at fetch time — not a live ticking clock. */
    private fun formatCountdown(now: Instant, target: Instant): String? {
        if (target.isBefore(now)) return null
        val duration = Duration.between(now, target)
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        return if (days > 0) "in ${days}d ${hours}h ${minutes}m" else "in ${hours}h ${minutes}m"
    }

    private fun post(body: JSONObject, accessToken: String? = null): JSONObject? {
        val requestBuilder = Request.Builder()
            .url(API_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer $accessToken")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseBody = response.body?.string() ?: return null
            return JSONObject(responseBody)
        }
    }
}
